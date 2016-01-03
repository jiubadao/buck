/*
 * Copyright 2013-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.android;

import com.facebook.buck.android.StringResources.Gender;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.XmlDomParser;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Maps;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

/**
 * This {@link Step} takes a list of string resource files (strings.xml), groups them by locales,
 * and for each locale generates a file with all the string resources for that locale.
 * Strings.xml files without a resource qualifier are mapped to the "en" locale.
 *
 * <p>A typical strings.xml file looks like:
 * <pre>
 *   {@code
 *   <?xml version="1.0" encoding="utf-8"?>
 *   <resources>
 *     <string name="resource_name1">I am a string.</string>
 *     <string name="resource_name2">I am another string.</string>
 *     <plurals name="time_hours_ago">
 *       <item quantity="one">1 minute ago</item>
 *       <item quantity="other">%d minutes ago</item>
 *     </plurals>
 *     <string-array name="logging_levels">
 *       <item>Default</item>
 *       <item>Verbose</item>
 *       <item>Debug</item>
 *     </string-array>
 *   </resources>
 *   }
 * </pre>
 *
 * <p>For more information on the xml file format, refer to:
 * <a href="http://developer.android.com/guide/topics/resources/string-resource.html">
 *   String Resources - Android Developers
 * </a></p>
 *
 * <p>So for each supported locale in a project, this step goes through all such xml files for that
 * locale, and builds a map of resource name to resource value, where resource value is either:
 * <ol>
 *   <li> a string </li>
 *   <li> a map of plurals </li>
 *   <li> a list of strings </li>
 * </ol>
 * and dumps this map into the output file. See {@link StringResources} for the file format.
 */
public class CompileStringsStep implements Step {

  private static final String ENGLISH_STRING_PATH_SUFFIX = "res/values/strings.xml";
  private static final String ENGLISH_LOCALE = "en";
  private static final String FEMALE_SUFFIX = "_f1gender";
  private static final String MALE_SUFFIX = "_m2gender";
  private static final int FEMALE_SUFFIX_LENGTH = FEMALE_SUFFIX.length();
  private static final int MALE_SUFFIX_LENGTH = MALE_SUFFIX.length();

  @VisibleForTesting
  static final Pattern NON_ENGLISH_STRING_FILE_PATTERN = Pattern.compile(
      ".*res/values-([a-z]{2})(?:-r([A-Z]{2}))*/strings.xml");

  @VisibleForTesting
  static final Pattern R_DOT_TXT_STRING_RESOURCE_PATTERN = Pattern.compile(
      "^int (string|plurals|array) (\\w+) 0x([0-9a-f]+)$");

  private final ProjectFilesystem filesystem;
  private final ImmutableList<Path> stringFiles;
  private final Path rDotTxtDir;
  private final Map<String, String> regionSpecificToBaseLocaleMap;
  private final Map<String, Integer> stringResourceNameToIdMap;
  private final Map<String, Integer> pluralsResourceNameToIdMap;
  private final Map<String, Integer> arrayResourceNameToIdMap;
  private final Function<String, Path> pathBuilder;

  /**
   * Note: The ordering of files in the input list determines which resource value ends up in the
   * output .fbstr file, in the event of multiple xml files of a locale sharing the same string
   * resource name - file that appears first in the list wins.
   *
   * @param stringFiles Set containing paths to strings.xml files matching
   *                    {@link GetStringsFilesStep#STRINGS_FILE_PATH}
   * @param rDotTxtDir Path to the directory where aapt generates R.txt file along with the
   *     final R.java files per package.
   * @param pathBuilder Builds a path to store a .fbstr file at.
   */
  public CompileStringsStep(
      ProjectFilesystem filesystem,
      ImmutableList<Path> stringFiles,
      Path rDotTxtDir,
      Function<String, Path> pathBuilder) {
    this.filesystem = filesystem;
    this.stringFiles = stringFiles;
    this.rDotTxtDir = rDotTxtDir;
    this.pathBuilder = pathBuilder;
    this.regionSpecificToBaseLocaleMap = Maps.newHashMap();
    this.stringResourceNameToIdMap = Maps.newHashMap();
    this.pluralsResourceNameToIdMap = Maps.newHashMap();
    this.arrayResourceNameToIdMap = Maps.newHashMap();
  }

  @Override
  public int execute(ExecutionContext context) {
    try {
      buildResourceNameToIdMap(
          filesystem,
          rDotTxtDir.resolve("R.txt"),
          stringResourceNameToIdMap,
          pluralsResourceNameToIdMap,
          arrayResourceNameToIdMap);
    } catch (IOException e) {
      context.logError(e, "Failure parsing R.txt file.");
      return 1;
    }

    ImmutableMultimap<String, Path> filesByLocale = groupFilesByLocale(stringFiles);
    Map<String, StringResources> resourcesByLocale = Maps.newHashMap();
    for (String locale : filesByLocale.keySet()) {
      try {
        resourcesByLocale.put(locale, compileStringFiles(filesystem, filesByLocale.get(locale)));
      } catch (IOException | SAXException e) {
        context.logError(e, "Error parsing string file for locale: %s", locale);
        return 1;
      }
    }

    // Merge region specific locale resources with the corresponding base locale resources.
    //
    // For example, if there are separate string resources in an android project for locale
    // "es" and "es_US", when an application running on a device with locale set to "Spanish
    // (United States)" requests for a string, the Android runtime first looks for the string in
    // "es_US" set of resources, and if not found, returns the resource from the "es" set.
    // We merge these because we want the individual .fbstr files to be self contained for
    // simplicity.
    for (String regionSpecificLocale : regionSpecificToBaseLocaleMap.keySet()) {
      String baseLocale = regionSpecificToBaseLocaleMap.get(regionSpecificLocale);
      if (!resourcesByLocale.containsKey(baseLocale)) {
        continue;
      }

      resourcesByLocale.put(regionSpecificLocale,
          resourcesByLocale.get(regionSpecificLocale)
              .getMergedResources(resourcesByLocale.get(baseLocale)));
    }

    for (String locale : filesByLocale.keySet()) {
      try {
        filesystem.writeBytesToPath(
            Preconditions.checkNotNull(resourcesByLocale.get(locale)).getBinaryFileContent(),
            pathBuilder.apply(locale));
      } catch (IOException e) {
        context.logError(e, "Error creating binary file for locale: %s", locale);
        return 1;
      }
    }

    return 0;
  }

  /**
   * Groups a list of strings.xml files by locale.
   * String files with no resource qualifier (eg. values/strings.xml) are mapped to the "en" locale
   *
   * eg. given the following list:
   *
   * ImmutableList.of(
   *   Paths.get("one/res/values-es/strings.xml"),
   *   Paths.get("two/res/values-es/strings.xml"),
   *   Paths.get("three/res/values-pt-rBR/strings.xml"),
   *   Paths.get("four/res/values-pt-rPT/strings.xml"),
   *   Paths.get("five/res/values/strings.xml"));
   *
   * returns:
   *
   * ImmutableMap.of(
   *   "es", ImmutableList.of(Paths.get("one/res/values-es/strings.xml"),
   *        Paths.get("two/res/values-es/strings.xml")),
   *   "pt_BR", ImmutableList.of(Paths.get("three/res/values-pt-rBR/strings.xml'),
   *   "pt_PT", ImmutableList.of(Paths.get("four/res/values-pt-rPT/strings.xml"),
   *   "en", ImmutableList.of(Paths.get("five/res/values/strings.xml")));
   */
  @VisibleForTesting
  ImmutableMultimap<String, Path> groupFilesByLocale(ImmutableList<Path> files) {
    ImmutableMultimap.Builder<String, Path> localeToFiles = ImmutableMultimap.builder();

    for (Path filepath : files) {
      String path = MorePaths.pathWithUnixSeparators(filepath);
      Matcher matcher = NON_ENGLISH_STRING_FILE_PATTERN.matcher(path);

      if (matcher.matches()) {
        String baseLocale = matcher.group(1);
        String country = matcher.group(2);
        String locale = country == null ? baseLocale : baseLocale + "_" + country;
        if (country != null && !regionSpecificToBaseLocaleMap.containsKey(locale)) {
          regionSpecificToBaseLocaleMap.put(locale, baseLocale);
        }

        localeToFiles.put(locale, filepath);
      } else {
        Preconditions.checkState(
            path.endsWith(ENGLISH_STRING_PATH_SUFFIX),
            "Invalid path passed to compile strings: " + path);

        localeToFiles.put(ENGLISH_LOCALE, filepath);
      }
    }

    return localeToFiles.build();
  }

  /**
   * Parses the R.txt file generated by aapt, looks for resources of type {@code string},
   * {@code plurals} and {@code array}, and builds a map of resource names to their corresponding
   * ids.
   */
  public static void buildResourceNameToIdMap(
      ProjectFilesystem filesystem,
      Path pathToRDotTxtFile,
      Map<String, Integer> stringResourceNameToIdMap,
      Map<String, Integer> pluralsResourceNameToIdMap,
      Map<String, Integer> arrayResourceNameToIdMap
  ) throws IOException {
    List<String> fileLines = filesystem.readLines(pathToRDotTxtFile);
    for (String line : fileLines) {
      Matcher matcher = R_DOT_TXT_STRING_RESOURCE_PATTERN.matcher(line);
      if (!matcher.matches()) {
        continue;
      }

      String type = matcher.group(1);
      String resourceName = matcher.group(2);
      Integer resourceId = Integer.parseInt(matcher.group(3), 16);
      switch (type) {
        case "string":
          stringResourceNameToIdMap.put(resourceName, resourceId);
          break;
        case "plurals":
          pluralsResourceNameToIdMap.put(resourceName, resourceId);
          break;
        case "array":
          arrayResourceNameToIdMap.put(resourceName, resourceId);
          break;
        default:
          throw new IllegalArgumentException("Invalid resource type: " + type);
      }
    }
  }

  private StringResources compileStringFiles(
      ProjectFilesystem filesystem,
      Collection<Path> filepaths) throws IOException, SAXException {
    TreeMap<Integer, EnumMap<Gender, String>> stringsMap = Maps.newTreeMap();
    TreeMap<Integer, EnumMap<Gender, ImmutableMap<String, String>>> pluralsMap = Maps.newTreeMap();
    TreeMap<Integer, EnumMap<Gender, ImmutableList<String>>> arraysMap = Maps.newTreeMap();

    for (Path stringFilePath : filepaths) {
      Document dom = XmlDomParser.parse(filesystem.getPathForRelativePath(stringFilePath));

      NodeList stringNodes = dom.getElementsByTagName("string");
      scrapeStringNodes(stringNodes, stringsMap);

      NodeList pluralNodes = dom.getElementsByTagName("plurals");
      scrapePluralsNodes(pluralNodes, pluralsMap);

      NodeList arrayNodes = dom.getElementsByTagName("string-array");
      scrapeStringArrayNodes(arrayNodes, arraysMap);
    }

    return new StringResources(stringsMap, pluralsMap, arraysMap);
  }


  /**
   * Scrapes string resource names and values from the list of xml nodes passed and populates
   * {@code stringsMap}, ignoring resource names that are already present in the map.
   *
   * @param stringNodes A list of {@code <string></string>} nodes.
   * @param stringsMap Map from string resource id to its values.
   */
  @VisibleForTesting
  void scrapeStringNodes(NodeList stringNodes, Map<Integer, EnumMap<Gender, String>> stringsMap) {
    for (int i = 0; i < stringNodes.getLength(); ++i) {
      Element element = (Element) stringNodes.item(i);
      String resourceName = element.getAttribute("name");
      Gender gender = getGender(element);

      Integer resId = getResourceId(resourceName, gender, stringResourceNameToIdMap);
      // Ignore a resource if R.txt does not contain an entry for it.
      if (resId == null) {
        continue;
      }

      EnumMap<Gender, String> genderMap = stringsMap.get(resId);
      if (genderMap == null)  {
        genderMap = Maps.newEnumMap(Gender.class);
      } else if (genderMap.containsKey(gender)) { // Ignore a resource if it has already been found
        continue;
      }

      genderMap.put(gender, element.getTextContent());
      stringsMap.put(resId, genderMap);
    }
  }

  /**
   * Similar to {@code scrapeStringNodes}, but for plurals nodes.
   */
  @VisibleForTesting
  void scrapePluralsNodes(
      NodeList pluralNodes,
      Map<Integer, EnumMap<Gender, ImmutableMap<String, String>>> pluralsMap) {

    for (int i = 0; i < pluralNodes.getLength(); ++i) {
      Element element = (Element) pluralNodes.item(i);
      String resourceName = element.getAttribute("name");
      Gender gender = getGender(element);

      Integer resourceId = getResourceId(resourceName, gender, pluralsResourceNameToIdMap);
      // Ignore a resource if R.txt does not contain an entry for it.
      if (resourceId == null) {
        continue;
      }

      EnumMap<Gender, ImmutableMap<String, String>> genderMap = pluralsMap.get(resourceId);
      if (genderMap == null)  {
        genderMap = Maps.newEnumMap(Gender.class);
      } else if (genderMap.containsKey(gender)) { // Ignore a resource if it has already been found
        continue;
      }

      ImmutableMap.Builder<String, String> quantityToStringBuilder = ImmutableMap.builder();

      NodeList itemNodes = element.getElementsByTagName("item");
      for (int j = 0; j < itemNodes.getLength(); ++j) {
        Node itemNode = itemNodes.item(j);
        String quantity = itemNode.getAttributes().getNamedItem("quantity").getNodeValue();
        quantityToStringBuilder.put(quantity, itemNode.getTextContent());
      }

      genderMap.put(gender, quantityToStringBuilder.build());
      pluralsMap.put(resourceId, genderMap);
    }
  }

  /**
   * Similar to {@code scrapeStringNodes}, but for string array nodes.
   */
  @VisibleForTesting
  void scrapeStringArrayNodes(
      NodeList arrayNodes,
      Map<Integer, EnumMap<Gender, ImmutableList<String>>> arraysMap) {

    for (int i = 0; i < arrayNodes.getLength(); ++i) {
      Element element = (Element) arrayNodes.item(i);
      String resourceName = element.getAttribute("name");
      Gender gender = getGender(element);

      Integer resourceId = getResourceId(resourceName, gender, arrayResourceNameToIdMap);
      // Ignore a resource if R.txt does not contain an entry for it.
      if (resourceId == null) {
        continue;
      }

      EnumMap<Gender, ImmutableList<String>> genderMap = arraysMap.get(resourceId);
      if (genderMap == null)  {
        genderMap = Maps.newEnumMap(Gender.class);
      } else if (genderMap.containsKey(gender)) { // Ignore a resource if it has already been found
        continue;
      }

      ImmutableList.Builder<String> arrayValues = ImmutableList.builder();
      NodeList itemNodes = element.getElementsByTagName("item");
      if (itemNodes.getLength() == 0) {
        continue;
      }
      for (int j = 0; j < itemNodes.getLength(); ++j) {
        arrayValues.add(itemNodes.item(j).getTextContent());
      }

      genderMap.put(gender, arrayValues.build());
      arraysMap.put(resourceId, genderMap);
    }
  }

  /**
   * Used in unit tests to inject the resource name to id map.
   */
  @VisibleForTesting
  void addStringResourceNameToIdMap(Map<String, Integer> nameToIdMap) {
    stringResourceNameToIdMap.putAll(nameToIdMap);
  }

  @VisibleForTesting
  void addPluralsResourceNameToIdMap(Map<String, Integer> nameToIdMap) {
    pluralsResourceNameToIdMap.putAll(nameToIdMap);
  }

  @VisibleForTesting
  void addArrayResourceNameToIdMap(Map<String, Integer> nameToIdMap) {
    arrayResourceNameToIdMap.putAll(nameToIdMap);
  }

  @Override
  public String getShortName() {
    return "compile_strings";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return "Combine, parse string resource xml files into one binary file per locale.";
  }

  @Nullable private static Integer getResourceId(
      String name,
      Gender gender,
      Map<String, Integer> resourceToIdMap) {
    Integer resId = null;
    if (name.endsWith(FEMALE_SUFFIX) && gender.equals(Gender.female)) {
      resId = resourceToIdMap.get(name.substring(0, name.length() - FEMALE_SUFFIX_LENGTH));
    } else if (name.endsWith(MALE_SUFFIX) && gender.equals(Gender.male)) {
      resId = resourceToIdMap.get(name.substring(0, name.length() - MALE_SUFFIX_LENGTH));
    }

    if (resId == null) {
      resId = resourceToIdMap.get(name);
    }

    return resId;
  }

  /**
   * Returns the Gender present in the passed in element's attribute, defaults to unknown gender
   * @param element the element for which gender attribute is to be determined
   * @return gender present in the element and unknown gender if not
   */
  private static Gender getGender(Element element) {
    Gender gender = Gender.unknown;
    boolean hasGender = element.hasAttribute("gender");
    if (hasGender) {
      gender = Gender.valueOf(element.getAttribute("gender"));
    }
    return gender;
  }
}
