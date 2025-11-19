package fdu.secsys.microservice;

/**
 * Configuration class for MScan analysis tool
 * Contains static configuration fields used throughout the analysis
 */
public class Config {

    /**
     * Name of the project being analyzed
     */
    public static String name;

    /**
     * Keywords used to filter application classes (e.g., package prefixes)
     */
    public static String[] classpathKeywords;

    /**
     * Path to the directory containing JAR files to analyze
     */
    public static String jarPath;

    /**
     * Target path for extracted files and analysis output
     */
    public static String targetPath;

    /**
     * Whether to reuse existing extracted files (true) or extract fresh (false)
     */
    public static boolean reuse;

    /**
     * Path to the Tai-e options file for analysis configuration
     */
    public static String optionsFile;

    /**
     * Path to route configuration file (if applicable)
     */
    public static String routeConfigFile = "";

}
