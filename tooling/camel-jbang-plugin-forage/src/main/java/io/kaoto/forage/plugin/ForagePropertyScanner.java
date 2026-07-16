package io.kaoto.forage.plugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import io.kaoto.forage.catalog.reader.ForageCatalogReader;

/**
 * Scans a directory for forage properties files and extracts property values
 * grouped by factory type key.
 */
public final class ForagePropertyScanner {

    private static final Pattern FORAGE_PROPERTY_PATTERN = Pattern.compile("^forage\\.(.+)$");
    /** The shared application properties file name. */
    public static final String APPLICATION_PROPERTIES = "application.properties";

    private static final String PROFILE_PROPERTY = "camel.main.profile";
    private static final String PROFILE_ENV_VAR = "CAMEL_MAIN_PROFILE";

    private ForagePropertyScanner() {}

    /**
     * Tracks where a property was defined (for validation purposes).
     */
    public record PropertyOccurrence(File file, String fullPropertyName, String value) {}

    /**
     * Scans the given directory for forage properties and returns them grouped by factory type key.
     * Each factory key maps to a property name to a list of all values found for that property
     * (supporting multiple named instances like ds1.jdbc.db.kind=mysql, ds2.jdbc.db.kind=postgresql).
     *
     * @param directory the directory to scan
     * @param catalog the catalog reader
     * @return map of factoryTypeKey -> (propertyName -> [values])
     */
    public static Map<String, Map<String, List<String>>> scanProperties(File directory, ForageCatalogReader catalog)
            throws IOException {
        // Delegate to scanPropertiesWithFileTracking and convert PropertyOccurrence to String
        Map<String, Map<String, List<PropertyOccurrence>>> withTracking =
                scanPropertiesWithFileTracking(directory, catalog, false);

        Map<String, Map<String, List<String>>> result = new HashMap<>();
        for (Map.Entry<String, Map<String, List<PropertyOccurrence>>> factoryEntry : withTracking.entrySet()) {
            String factoryKey = factoryEntry.getKey();

            Map<String, List<String>> propertyMap = new HashMap<>();
            for (Map.Entry<String, List<PropertyOccurrence>> propEntry :
                    factoryEntry.getValue().entrySet()) {
                List<String> values = propEntry.getValue().stream()
                        .map(PropertyOccurrence::value)
                        .toList();
                propertyMap.put(propEntry.getKey(), values);
            }
            result.put(factoryKey, propertyMap);
        }

        return result;
    }

    /**
     * Scans properties files and tracks where each property is defined.
     * Returns a map of factory type key to properties, plus optionally a special "__unknown__" key for unparseable properties.
     *
     * @param directory the directory to scan
     * @param catalog the catalog reader
     * @param trackUnknown if true, unparseable forage properties are tracked in "__unknown__" bucket
     * @return map of factoryTypeKey -> (propertyName -> [PropertyOccurrence])
     * @throws IOException if properties files cannot be read
     */
    public static Map<String, Map<String, List<PropertyOccurrence>>> scanPropertiesWithFileTracking(
            File directory, ForageCatalogReader catalog, boolean trackUnknown) throws IOException {

        Map<String, Map<String, List<PropertyOccurrence>>> result = new HashMap<>();

        // Load application properties with profile override semantics (like Camel does):
        // scan base first, then profile file — profile occurrences replace base ones per property key
        File applicationPropsFile = loadApplicationProperties(directory);
        Properties baseProps = null;
        if (applicationPropsFile != null) {
            baseProps = new Properties();
            try (FileInputStream fis = new FileInputStream(applicationPropsFile)) {
                baseProps.load(fis);
            }
            collectForageProperties(baseProps, applicationPropsFile, catalog, trackUnknown, result);
        }

        File profileFile = findProfileProperties(directory, baseProps);
        if (profileFile != null) {
            Properties profileProps = new Properties();
            try (FileInputStream fis = new FileInputStream(profileFile)) {
                profileProps.load(fis);
            }
            // Profile properties override base: replace matching occurrences
            overrideForageProperties(profileProps, profileFile, catalog, trackUnknown, result);
        }

        // Load forage-specific and catalog-named properties files independently
        List<File> nonAppFiles = findForagePropertiesFiles(directory, catalog);
        for (File file : nonAppFiles) {
            Properties props = new Properties();
            try (FileInputStream fis = new FileInputStream(file)) {
                props.load(fis);
            }
            collectForageProperties(props, file, catalog, trackUnknown, result);
        }

        return result;
    }

    /**
     * Scans properties files and tracks where each property is defined.
     * Unparseable properties are tracked in "__unknown__" bucket.
     *
     * @param directory the directory to scan
     * @param catalog the catalog reader
     * @return map of factoryTypeKey -> (propertyName -> [PropertyOccurrence])
     * @throws IOException if properties files cannot be read
     */
    public static Map<String, Map<String, List<PropertyOccurrence>>> scanPropertiesWithFileTracking(
            File directory, ForageCatalogReader catalog) throws IOException {
        return scanPropertiesWithFileTracking(directory, catalog, true);
    }

    private static void collectForageProperties(
            Properties props,
            File sourceFile,
            ForageCatalogReader catalog,
            boolean trackUnknown,
            Map<String, Map<String, List<PropertyOccurrence>>> result) {

        for (String key : props.stringPropertyNames()) {
            Matcher matcher = FORAGE_PROPERTY_PATTERN.matcher(key);
            if (!matcher.matches()) {
                continue;
            }

            String remainder = matcher.group(1);
            ParseResult parsed = parsePropertyKey(remainder, catalog);

            String value = props.getProperty(key);
            PropertyOccurrence occurrence = new PropertyOccurrence(sourceFile, key, value);

            if (parsed == null) {
                if (trackUnknown) {
                    result.computeIfAbsent("__unknown__", k -> new HashMap<>())
                            .computeIfAbsent(remainder, k -> new ArrayList<>())
                            .add(occurrence);
                }
                continue;
            }

            result.computeIfAbsent(parsed.factoryTypeKey, k -> new HashMap<>())
                    .computeIfAbsent(parsed.propertyName, k -> new ArrayList<>())
                    .add(occurrence);
        }
    }

    private static void overrideForageProperties(
            Properties profileProps,
            File profileFile,
            ForageCatalogReader catalog,
            boolean trackUnknown,
            Map<String, Map<String, List<PropertyOccurrence>>> result) {

        for (String key : profileProps.stringPropertyNames()) {
            Matcher matcher = FORAGE_PROPERTY_PATTERN.matcher(key);
            if (!matcher.matches()) {
                continue;
            }

            String remainder = matcher.group(1);
            ParseResult parsed = parsePropertyKey(remainder, catalog);

            String value = profileProps.getProperty(key);
            PropertyOccurrence occurrence = new PropertyOccurrence(profileFile, key, value);

            if (parsed == null) {
                if (trackUnknown) {
                    replaceBaseOccurrence("__unknown__", remainder, key, occurrence, result);
                }
                continue;
            }

            replaceBaseOccurrence(parsed.factoryTypeKey, parsed.propertyName, key, occurrence, result);
        }
    }

    // Override is only called after collectForageProperties has processed application.properties,
    // and before non-application files (forage-*.properties) are scanned. This ordering guarantees
    // that removeIf only removes base application.properties occurrences, never forage-*.properties ones.
    private static void replaceBaseOccurrence(
            String groupKey,
            String propertyName,
            String fullPropertyName,
            PropertyOccurrence occurrence,
            Map<String, Map<String, List<PropertyOccurrence>>> result) {

        Map<String, List<PropertyOccurrence>> groupMap = result.computeIfAbsent(groupKey, k -> new HashMap<>());
        List<PropertyOccurrence> occurrences = groupMap.get(propertyName);
        if (occurrences != null) {
            occurrences.removeIf(o -> o.fullPropertyName().equals(fullPropertyName));
        }
        groupMap.computeIfAbsent(propertyName, k -> new ArrayList<>()).add(occurrence);
    }

    /**
     * Returns the {@code application.properties} file of the given directory.
     *
     * @param dir the directory to look in
     * @return the file, or {@code null} if it does not exist
     */
    public static File loadApplicationProperties(File dir) {
        File file = new File(dir, APPLICATION_PROPERTIES);
        return file.isFile() ? file : null;
    }

    /**
     * Returns the {@code application-<profile>.properties} file for the active Camel profile, resolved from
     * {@code camel.main.profile} / {@code CAMEL_MAIN_PROFILE} or the given base properties.
     *
     * @return the profile file, or {@code null} if no profile is active or the file does not exist
     */
    private static File findProfileProperties(File dir, Properties applicationProps) {
        String profile = resolveProfile(applicationProps);
        if (profile == null) {
            return null;
        }
        File file = new File(dir, "application-" + profile + ".properties");
        return file.isFile() ? file : null;
    }

    /**
     * Returns every properties file the scanner loads Forage configuration from, in loading order:
     * {@code application.properties}, the active profile's {@code application-<profile>.properties}, and the
     * files returned by {@link #findForagePropertiesFiles(File, ForageCatalogReader)}.
     *
     * @param dir the directory to scan
     * @param catalog the catalog reader
     * @return the existing files, never {@code null}
     * @throws IOException if a file cannot be read or the directory cannot be scanned
     */
    public static List<File> findRuntimePropertiesFiles(File dir, ForageCatalogReader catalog) throws IOException {
        List<File> files = new ArrayList<>();
        File applicationPropsFile = loadApplicationProperties(dir);
        Properties baseProps = null;
        if (applicationPropsFile != null) {
            files.add(applicationPropsFile);
            baseProps = new Properties();
            try (FileInputStream fis = new FileInputStream(applicationPropsFile)) {
                baseProps.load(fis);
            }
        }
        File profileFile = findProfileProperties(dir, baseProps);
        if (profileFile != null) {
            files.add(profileFile);
        }
        files.addAll(findForagePropertiesFiles(dir, catalog));
        return files;
    }

    /**
     * Resolves the factory type key a {@code forage.*} property belongs to, using the same parsing as the
     * scanner. Handles both default ({@code forage.jdbc.url}) and named ({@code forage.ds1.jdbc.url})
     * instances.
     *
     * @param propertyKey the full property key
     * @param catalog the catalog reader
     * @return the factory type key, or empty if the key is not a recognised Forage property
     */
    public static Optional<String> findFactoryTypeKey(String propertyKey, ForageCatalogReader catalog) {
        Matcher matcher = FORAGE_PROPERTY_PATTERN.matcher(propertyKey);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return Optional.ofNullable(parsePropertyKey(matcher.group(1), catalog)).map(ParseResult::factoryTypeKey);
    }

    static String resolveProfile(Properties applicationProps) {
        String profile = resolveProfileFromEnvironment();
        if (profile != null) {
            return profile;
        }

        if (applicationProps != null) {
            profile = applicationProps.getProperty(PROFILE_PROPERTY);
        }
        return profile;
    }

    private static String resolveProfileFromEnvironment() {
        String profile = System.getProperty(PROFILE_PROPERTY);
        if (profile != null) {
            return profile;
        }
        return System.getenv(PROFILE_ENV_VAR);
    }

    /**
     * Finds the non-application Forage properties files in the given directory: the catalog-known file names
     * (one per module, named after its {@code Config.name()}) plus any {@code forage-*.properties} file. Only the
     * top level of the directory is scanned.
     *
     * <p>Note that the {@code forage-*.properties} wildcard is only loaded at runtime on Spring Boot; plain Camel
     * and Quarkus only load the catalog-known file names (and {@code application.properties}).
     *
     * @param dir the directory to scan
     * @param catalog the catalog reader
     * @return the matching files, never {@code null}
     * @throws IOException if the directory cannot be scanned
     */
    public static List<File> findForagePropertiesFiles(File dir, ForageCatalogReader catalog) throws IOException {
        Set<String> catalogFileNames = new java.util.HashSet<>();

        for (ForageCatalogReader.FactoryMetadata metadata : catalog.getAllFactories()) {
            String propsFile = metadata.propertiesFileName();
            if (propsFile != null && !propsFile.isEmpty()) {
                catalogFileNames.add(propsFile);
            }
        }

        try (Stream<Path> paths = Files.walk(dir.toPath(), 1)) {
            return paths.filter(Files::isRegularFile)
                    .filter(p -> {
                        String fileName = p.getFileName().toString();
                        return catalogFileNames.contains(fileName)
                                || (fileName.startsWith("forage-") && fileName.endsWith(".properties"));
                    })
                    .map(Path::toFile)
                    .toList();
        }
    }

    private static ParseResult parsePropertyKey(String key, ForageCatalogReader catalog) {
        String[] parts = key.split("\\.", 2);
        if (parts.length < 2) {
            return null;
        }

        String firstPart = parts[0];
        String restOfKey = parts[1];

        // Check if the first part is a known factory type key
        if (catalog.getFactoryMetadata(firstPart).isPresent()) {
            return new ParseResult(firstPart, restOfKey);
        }

        // Check if the first part is a known bean type
        Optional<String> beanFactory = catalog.findFactoryTypeKeyForBeanName(firstPart);
        if (beanFactory.isPresent()) {
            return new ParseResult(beanFactory.get(), key);
        }

        // The first part might be an instance name, check second segment
        String[] restParts = restOfKey.split("\\.", 2);
        if (restParts.length >= 1) {
            String secondPart = restParts[0];
            String propertyName = restParts.length > 1 ? restParts[1] : "";

            if (catalog.getFactoryMetadata(secondPart).isPresent()) {
                return new ParseResult(secondPart, propertyName);
            }

            Optional<String> beanFactory2 = catalog.findFactoryTypeKeyForBeanName(secondPart);
            if (beanFactory2.isPresent()) {
                return new ParseResult(beanFactory2.get(), restOfKey);
            }
        }

        // Try property prefix mapping
        Optional<String> factoryType = catalog.findFactoryTypeKeyForPropertyPrefix(firstPart);
        return factoryType.map(s -> new ParseResult(s, key)).orElse(null);
    }

    private record ParseResult(String factoryTypeKey, String propertyName) {}
}
