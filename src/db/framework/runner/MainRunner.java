package db.framework.runner;

import com.google.gson.Gson;
import db.framework.utils.ProxyFilters;
import db.framework.utils.Utils;
import net.lightbody.bmp.BrowserMobProxy;
import net.lightbody.bmp.BrowserMobProxyServer;
import net.lightbody.bmp.client.ClientUtil;
import org.apache.commons.lang3.StringUtils;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.Proxy;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.edge.EdgeDriver;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.ie.InternetExplorerDriver;
import org.openqa.selenium.remote.CapabilityType;
import org.openqa.selenium.remote.DesiredCapabilities;
import org.openqa.selenium.safari.SafariDriver;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.lang.Runtime.getRuntime;

/**
 * This class handles the configuration and running of cucumber scenarios and features
 */
public class MainRunner {
    /**
     * BrowserMob proxy server
     */
    public static BrowserMobProxy browsermobServer = null;


    /**
     * Contains OS to use when executing on saucelabs as given in "remote_os" env variable
     * <p>
     * Options: windows 7|8|8.1|10, OSX 10.10|10.11
     * </p>
     */
    public static String remoteOS;

    /**
     * Workspace path as given in "WORKSPACE" env variable
     */
    public static String workspace;

    /**
     * Path to logging folder
     */
    public static String logs;

    /**
     * Path to "temp" directory
     */
    public static String temp;

    /**
     * Map of current cucumber features
     */
    public static HashMap features = new HashMap();

    /**
     * Path to feature file to execute from
     */
    public static String scenarios = getExParams("scenarios");

    /**
     * Browser to use as given in "browser" env variable. Default firefox.
     */
    public static String browser = "firefox";

    /**
     * Version of browser to use as given in "browser_version" env variable
     */
    public static String browserVersion = null;

    /**
     * Whether to close browser after testing is complete. False if "DEBUG" env variable is present
     */
    public static Boolean closeBrowserAtExit = true;

    /**
     * Whether to collect coremetrics tags or not as given in "tag_collection" env variable
     */
    public static Boolean tagCollection = false;

    /**
     * URL to start at and use as a base as given in "website" env variable
     */
    public static String url = "";

    /**
     * Time the tests were started
     */
    public static long startTime = System.currentTimeMillis();

    /**
     * Current run status. 0 is good, anything else is bad
     */
    public static int runStatus = 0;

    /**
     * Wait timeout as given in "timeout" env variable. Default 30 seconds
     */
    public static int timeout = 30; // set the general default timeout to 30 seconds


    /**
     * List containing URL's that have been visited
     */
    public static ArrayList<String> URLStack = new ArrayList<>();

    /**
     * Path to project currently being run
     */
    public static String project = null;
    /**
     * The current URL
     */
    public static String currentURL;
    /**
     * Whether the proxy is disabled
     */
    public static boolean disableProxy = true;

    private static WebDriver driver = null;
    private static long ieAuthenticationTs = System.currentTimeMillis() - 10000; // set authentication checking interval out of range
    private static String browsermobServerHarTs = System.currentTimeMillis() + "";

    /**
     * Gets whether or not debug mode is on
     *
     * @return true if debug mode is on
     */
    public static boolean isDebug() {
        String debug = getExParams("DEBUG");
        return debug != null && debug.matches("t|true");
    }

    /**
     * Resets the driver
     *
     * @param quit whether to close the driver
     */
    public static void resetDriver(boolean quit) {
        if (quit)
            MainRunner.driver.quit();
        MainRunner.driver = null;
    }

    /**
     * Checks if the web driver exists
     *
     * @return true if a valid web driver is active
     */
    public static Boolean driverInitialized() {
        return MainRunner.driver == null;
    }

    /**
     * Gets the current webDriver instance or tries to create one
     *
     * @return current webDriver instance
     */
    public static WebDriver getWebDriver() {
        try {
            if (MainRunner.driver != null) {
                currentURL = MainRunner.getCurrentUrl();
                if (!URLStack.get(URLStack.size() - 1).equals(currentURL))
                    URLStack.add(currentURL);
            }
        } catch (Exception e) {
        }

        if (MainRunner.driver != null)
            return MainRunner.driver;

        for (int i = 0; i < 2; i++) {
            if (MainRunner.disableProxy) {
                initDriver(null);
            } else {
                initProxyServer();
            }

            try {
                if (MainRunner.browser.equals("safari")) {
                    Dimension dimension = new Dimension(1280, 1024);
                    MainRunner.driver.manage().window().setSize(dimension);
                } else
                    MainRunner.driver.manage().window().maximize();

                String window_size = MainRunner.driver.manage().window().getSize().toString();
                System.out.println("Init driver: browser window size = " + window_size);
                return MainRunner.driver;
            } catch (Exception ex) {
                System.err.println("-->Failed initialized driver:retry" + i + ":" + ex.getMessage());
                Utils.threadSleep(2000, null);
            }
        }

        System.err.println("Cannot initialize driver: exiting test...");
        System.exit(-1);
        // return is unreachable but IDE doesn't realize, return non-null
        // to get rid of invalid lint errors
        return new ChromeDriver();
    }

    /**
     * Retrieves an environment variable OR ex_param
     *
     * @param param name of parameter to retrieve
     * @return value of parameter or null if not found
     */
    public static String getExParams(String param) {
        String value = System.getenv(param);
        if (value != null)
            return value;
        param += "=";
        try {
            String exparams = URLDecoder.decode(System.getenv("ex_params"), "utf-8");
            if (exparams != null && !exparams.isEmpty()) {
                StringBuilder sb = new StringBuilder(exparams);
                for (int i = 0, qindex = -1; i < sb.length(); i++) {
                    char c = sb.charAt(i);
                    if (c == '\"' && qindex == -1) {
                        qindex = i;
                    }
                    if (qindex > -1) {
                        for (i = i + 1; i < sb.length(); i++) {
                            c = sb.charAt(i);
                            if (c == '\"') {
                                qindex = -1;
                                break;
                            }
                            if (c == ' ') {
                                sb.setCharAt(i, '|');
                            }
                        }
                    }
                }
                exparams = sb.toString();
                String[] exParams = exparams.split(" ");
                for (String exParam : exParams) {
                    if (exParam.startsWith(param)) {
                        return exParam.split("=")[1].trim().replace('|', ' ').replace("\"", "");
                    }
                }
            }
        } catch (Exception ex) {
        }
        return null;
    }

    /**
     * Gets a list of all scenarios to be run
     *
     * @return the list of scenarios
     */
    public static ArrayList<String> getFeatureScenarios() {
        ArrayList<String> scenarioList = new ArrayList<>();
        if (scenarios == null)
            return scenarioList;
        scenarios = scenarios.trim();
        String delimit = ".feature:";
        int i = 0, end = scenarios.indexOf(delimit);
        while (i < scenarios.length()) {
            end = scenarios.indexOf(' ', end);
            if (end == -1)
                end = scenarios.length();
            String scenarioPath = scenarios.substring(i, end).trim();
            System.out.println("->" + scenarioPath);
            scenarioList.add(scenarioPath);
            i = end;
            end = scenarios.indexOf(delimit, i);
        }

        Collections.sort(scenarioList);
        ArrayList<Map> featureScenarios = null;
        String workSpace = getExParams("WORKSPACE");
        if (workSpace == null)
            workSpace = "";
        for (String featureFilePath : scenarioList) {
            String[] featureInfo = featureFilePath.split(".feature:");
            String path = featureInfo[0];
            if (!path.endsWith(".feature"))
                path += ".feature";
            int line = 0;
            if (featureInfo.length == 2)
                line = Utils.parseInt(featureInfo[1], 0);
            if (!path.equals("")) {
                File featureFile = new File(path);
                if (!(featureFile.exists() || featureFile.getAbsoluteFile().exists())) {
                    System.err.println("File not found: " + path);
                    path = workSpace + "/" + path;
                }
                featureScenarios = new Gson().fromJson(Utils.gherkinTojson(false, path), ArrayList.class);
            }
            findScenario(featureScenarios, path, line);
            features.putIfAbsent(path, 0);
        }
        return scenarioList;
    }

    /**
     * Closes a firefox alert if present
     */
    public static void closeAlert() {
        if (MainRunner.driver != null) {
            try {
                MainRunner.driver.switchTo().alert().accept();
            } catch (org.openqa.selenium.NoAlertPresentException e) {
                System.out.println("No alert to close");
            }
        }
    }

    private static DesiredCapabilities disabledProxyCap(DesiredCapabilities desiredCap) {
        if (MainRunner.disableProxy) {
            desiredCap.setCapability(CapabilityType.ForSeleniumServer.AVOIDING_PROXY, true);
            desiredCap.setCapability(CapabilityType.ForSeleniumServer.PROXYING_EVERYTHING, false);
        }
        return desiredCap;
    }

    private static DesiredCapabilities initCapabilities() {
        switch (MainRunner.browser) {
            case "ie":
                DesiredCapabilities ieCapabilities = DesiredCapabilities.internetExplorer();
                ieCapabilities.setCapability(InternetExplorerDriver.INITIAL_BROWSER_URL, true);
                ieCapabilities.setCapability(CapabilityType.ACCEPT_SSL_CERTS, true);
                ieCapabilities.setCapability(InternetExplorerDriver.ENABLE_PERSISTENT_HOVERING, false);
                ieCapabilities.setCapability(InternetExplorerDriver.REQUIRE_WINDOW_FOCUS, true);
                ieCapabilities.setCapability(InternetExplorerDriver.NATIVE_EVENTS, false);
                return disabledProxyCap(ieCapabilities);
            case "chrome":
                DesiredCapabilities capabilities = DesiredCapabilities.chrome();
                ChromeOptions chrome = new ChromeOptions();
                chrome.addArguments("test-type");
                capabilities.setCapability(ChromeOptions.CAPABILITY, chrome);
                return disabledProxyCap(capabilities);
            case "safari":
                return disabledProxyCap(DesiredCapabilities.safari());
            case "edge":
                return DesiredCapabilities.edge();
            default:
                return disabledProxyCap(DesiredCapabilities.firefox());
        }
    }

    private static void initDriver(DesiredCapabilities capabilities) {
        if (capabilities == null)
            capabilities = initCapabilities();
        switch (MainRunner.browser) {
            case "ie":
                capabilities.setCapability("version", browserVersion);
                File file = new File(MainRunner.workspace + "src/db/framework/selenium_drivers/IEDriverServer.exe");
                if (!file.exists())
                    file = new File(MainRunner.workspace + "db/framework/selenium_drivers/IEDriverServer.exe");
                System.setProperty("webdriver.ie.driver", file.getAbsolutePath());
                driver = new InternetExplorerDriver(capabilities);
                break;
            case "chrome":
                capabilities.setCapability("version", browserVersion);
                String fileName = "chromedriver.exe";
                if (Utils.isOSX())
                    fileName = "chromedriver";
                file = new File(MainRunner.workspace + "src/db/framework/selenium_drivers/" + fileName);
                if (!file.exists())
                    file = new File(MainRunner.workspace + "db/framework/selenium_drivers/" + fileName);
                System.setProperty("webdriver.chrome.driver", file.getAbsolutePath());
                driver = new ChromeDriver(capabilities);
                break;
            case "safari":
                capabilities.setCapability("version", browserVersion);
                // safari driver is not stable, retry 3 times
                int count = 0;
                while (driver == null && count++ < 3)
                    try {
                        driver = new SafariDriver(capabilities);
                    } catch (Exception e) {
                        Utils.threadSleep(5000, null);
                    }
                break;
            case "edge":
                driver = new EdgeDriver(capabilities);
                break;
            default:
                driver = new FirefoxDriver(capabilities);
                break;
        }
        if (!MainRunner.browser.equals("safari")) {
            WebDriver.Timeouts to = driver.manage().timeouts();
            to.pageLoadTimeout(30, TimeUnit.SECONDS);
            to.setScriptTimeout(30, TimeUnit.SECONDS);
        }

        Utils.PageHangWatchDog.init();
    }

    private static String defaultBrowserVersion() {
        switch (MainRunner.browser) {
            case "ie":
                return "11.0";
            case "chrome":
                return "49.0";
            case "edge":
                return "20.10240";
            case "safari":
                String version;
                if (remoteOS == null)
                    version = "9.0";
                else if (remoteOS.contains("10.11"))
                    version = "9.0";
                else if (remoteOS.contains("10.10"))
                    version = "8.0";
                else if (remoteOS.contains("10.9"))
                    version = "7.0";
                else if (remoteOS.contains("10.8"))
                    version = "6.0";
                else
                    version = "0";
                return version;
            default:
                // assume firefox
                return "45.0";
        }
    }

    private static void initProxyServer() {
        if (browsermobServer != null) {
            System.err.println("-->Aborting prev proxy server:" + browsermobServer.getPort());
            try {
                browsermobServer.abort();
            } catch (Exception ex) {
                System.err.println("-->Failed to abort prev proxy server:" + browsermobServer.getPort());
            }
        }

        System.out.print("Initializing proxy server...");
        int port = 7000;
        boolean found = false;
        for (int i = 0; i < 10; i++) {
            try {
                browsermobServer = new BrowserMobProxyServer();
                browsermobServer.start(port);
                System.out.println("using port " + port);
                found = true;
                break;
            } catch (Exception ex) {
                System.out.println("port " + port + " is in use:" + ex.getMessage());
                port++;
            }
        }
        if (!found) {
            System.out.println("Cannot find open port for proxy server");
            System.out.println("Abort run.");
            System.exit(-1);
        }

        Proxy seleniumProxy = ClientUtil.createSeleniumProxy(browsermobServer);
        DesiredCapabilities capabilities = initCapabilities();
        capabilities.setCapability(CapabilityType.PROXY, seleniumProxy);
        initDriver(capabilities);
        browsermobServer.newHar(browsermobServerHarTs);
        browsermobServer.addRequestFilter(new ProxyFilters.ProxyRequestFilter(url));
        browsermobServer.addResponseFilter(new ProxyFilters.ProxyResponseFilter());
    }

    /**
     * Main method to run tests
     *
     * @param argv run args. Ignored, use environment variables for all config
     * @throws Throwable if an exception or error gets here, we're done
     */
    public static void main(String[] argv) throws Throwable {

        workspace = getExParams("WORKSPACE");
        if (workspace == null)
            workspace = ".";
        workspace = workspace.replace('\\', '/');
        workspace = workspace.endsWith("/") ? workspace : workspace + "/";
        Utils.createDirectory(logs = workspace + "/logs/", argv != null);
        Utils.createDirectory(temp = workspace + "/temp/", true);

        url = getExParams("website");
        remoteOS = getExParams("remote_os");
        if (remoteOS == null) {
            System.out.println("Remote OS not specified.  Using default: Windows 7");
            remoteOS = "Windows 7";
        }
        browser = getExParams("browser") != null ? getExParams("browser") : browser;
        browserVersion = getExParams("browser_version") != null ? getExParams("browser_version") : defaultBrowserVersion();
        System.out.println("-->Testing " + url + " with " + browser + " " + browserVersion);
        new AuthenticationDialog();

        System.out.println("\n\n");
        closeBrowserAtExit = !isDebug();

        // tag_collection
        String env_val = getExParams("tag_collection");
        tagCollection = false;
        if (env_val != null)
            tagCollection = env_val.toLowerCase().equals("true");
        if (tagCollection)
            System.out.println("tag_collection is enabled");


        // close the test browser at scenario exit
        env_val = getExParams("timeout");
        if (env_val != null) {
            int timeout = Integer.parseInt(env_val);
            if (timeout > 0 && timeout != MainRunner.timeout)
                MainRunner.timeout = timeout;
        }

        ArrayList<String> featureScenarios = getFeatureScenarios();
        if (featureScenarios == null)
            throw new Exception("Error getting scenarios");

        getWebDriver();

        HashMap<String, ArrayList<String>> hs = new HashMap<>();
        String firstScenario = (featureScenarios.size() > 0) ? featureScenarios.get(0) : "";
        for (String scenario : featureScenarios) {
            int lineIndex = scenario.lastIndexOf(':');
            if (lineIndex == -1)
                continue;
            String scenarioPath = scenario.substring(0, lineIndex).trim();
            String line = scenario.substring(lineIndex + 1);
            ArrayList<String> lines = hs.get(scenarioPath);
            if (lines == null) {
                lines = new ArrayList<>();
                hs.put(scenarioPath, lines);
            }
            lines.add(line);
        }

        featureScenarios.clear();
        featureScenarios = hs.keySet().stream()
                .map((key) -> key + ":" + StringUtils.join(hs.get(key), ":"))
                .collect(Collectors.toCollection(ArrayList<String>::new));

        if (featureScenarios.isEmpty())
            featureScenarios.add(firstScenario);

        String tags = getExParams("tags");
        if (tags != null) {
            tags = tags.trim();
            if (!tags.isEmpty()) {
                featureScenarios.add("--tags");
                featureScenarios.add(tags);
            }
        }

        // set a project
        env_val = getExParams("project");
        if (env_val != null) {
            int count = StringUtils.countMatches(env_val, ".");
            if (3 < count)
                project = env_val;
        }
        if (project == null) {
            String project_path = firstScenario.replace("/", ".").replace("\\", ".");
            String[] parts = project_path.split(Pattern.quote("."));
            int com_index = 0;
            for (int i = 0; i < parts.length; i++)
                if (parts[i].equals("db")) {
                    com_index = i;
                    break;
                }
            if (com_index != parts.length)
                project = parts[com_index] + "." +
                        parts[com_index + 1] + "." +
                        parts[com_index + 2] + "." +
                        parts[com_index + 3] + "." +
                        parts[com_index + 4] + "." +
                        parts[com_index + 5];
        }
        if (project != null)
            System.out.println("-->Current project: " + project);
        System.out.println("-->Running with parameters:\n" + featureScenarios);
        if (MainRunner.workspace != null && !MainRunner.workspace.isEmpty())
            for (int i = 0; i < featureScenarios.size(); i++) {
                String value = featureScenarios.get(i);
                if (value.equals("--tags"))
                    break;
                File featureFile = new File(value);
                if (!(featureFile.exists() || featureFile.getAbsoluteFile().exists()))
                    value = MainRunner.workspace + "/" + value;
                featureScenarios.set(i, value);
            }

        //--glue db.shared.steps --plugin json:logs/cucumber.json
        featureScenarios.add("--glue");
        if (project != null) {
            featureScenarios.add(project);
            featureScenarios.add("--glue");
        }
        featureScenarios.add("db.shared.steps");
        featureScenarios.add("--plugin");
        featureScenarios.add("html:logs");

        try {
            runStatus = cucumber.api.cli.Main.run(featureScenarios.toArray(new String[featureScenarios.size()]),
                    Thread.currentThread().getContextClassLoader());
        } catch (Throwable e) {
            e.printStackTrace();
            runStatus = 1;
        } finally {
            close();
            if (argv != null)
                System.exit(runStatus);
        }
    }

    private static boolean findScenario(ArrayList<Map> featureScenarios, String scenarioPath, int line) {
        for (Map scenario : featureScenarios) {
            ArrayList<Map> elements = (ArrayList) scenario.get("elements");
            for (Map element : elements) {
                int l = Utils.parseInt(element.get("line"), 0);
                if (line == 0 || line == l) {
                    element.put("uri", scenario.get("uri"));
                    features.put(scenarioPath + (line == 0 ? ":" + l : ""), element);
                    if (line == 0)
                        continue;
                    return true;
                }
            }
        }
        return false;
    }

    private static void close() {
        if (MainRunner.browser.equals("none"))
            return;
        else if (MainRunner.closeBrowserAtExit) {
            System.out.println("Closing driver...");
            if (MainRunner.driver != null)
                driverQuit();
        }
    }

    private static void driverQuit() {
        try {
            MainRunner.driver.quit();
        } catch (Exception e) {
        }
    }

    private static String getCurrentUrl() {
        // IE windows authentication popup disapears when MainRunner.driver.getCurrentUrl() executed
        // so need to hook the function and wait for 10 seconds to look for the IE window authentication popup
        // and repeat every 1 hour
        long cs = System.currentTimeMillis();
        // check first 10 seconds only
        if (cs - ieAuthenticationTs < 10000) {
            if (MainRunner.browser.equals("ie")
                    && MainRunner.getExParams("require_authentication") != null
                    && MainRunner.getExParams("require_authentication").equals("true")) {
                // check IE window authentication popup
                int exit_value = runIEMethod();
                // IE authentication popup login successfully, no more checking unitl next hour
                if (exit_value == 0)
                    ieAuthenticationTs -= 10000;
            }
        } else {
            // after that check every hour
            if (cs - ieAuthenticationTs > 3600000)
                ieAuthenticationTs = cs;
        }
        return (MainRunner.driver.getCurrentUrl());
    }

    /**
     * Initialize IE authentication
     */
    public static void authenticationIeInit() {
        ieAuthenticationTs = System.currentTimeMillis();
    }

    public static int runIEMethod() {
        Process p;
        String file_path = "src/db/framework/authentication_popup/windows_authentication_ie.exe";
        if (!new File("src").exists())
            file_path = "db/framework/authentication_popup/windows_authentication_ie.exe";

        try {
            p = getRuntime().exec(file_path);
            p.waitFor();  // wait for process to complete
            return (p.exitValue());
        } catch (Exception e) {
            // ignore all errors
        }
        return 1;
    }

    public static int runChromeMethod() {
        Process p;
        String file_path = "src/db/framework/authentication_popup/windows_authentication_chrome.exe";
        if (!new File("src").exists())
            file_path = "db/framework/authentication_popup/windows_authentication_chrome.exe";

        try {
            p = getRuntime().exec(file_path);
            p.waitFor();  // wait for process to complete
            return (p.exitValue());
        } catch (Exception e) {
            // ignore all errors
        }
        return 1;
    }

    // protected methods
    // windows authentication dialog login
    protected static class AuthenticationDialog extends Thread {
        private static ServerSocket m_socketMutex;

        public AuthenticationDialog() {
            String os_name = System.getProperty("os.name").toLowerCase();
            if (getExParams("require_authentication") == null) {
                System.out.println("AuthenticationDialog not required: "
                        + getExParams("require_authentication"));
                return;
            }
            if (!(Utils.isWindows() && MainRunner.browser.equals("firefox")) &&
                    !(Utils.isWindows() && MainRunner.browser.equals("chrome")) &&
                    !(Utils.isOSX() && MainRunner.browser.equals("safari"))) {
                System.out.println("AuthenticationDialog not required:"
                        + getExParams("require_authentication")
                        + ":" + os_name
                        + ":" + MainRunner.browser);
                return;
            }

            this.start();
            new Thread(() -> {
                switch (MainRunner.browser) {
                    case "firefox":
                        runFirefoxBackgroundMethod();
                        break;
                    case "safari":
                        runSafariBackgroundMethod();
                        break;
                    case "chrome":
                        runChromeBackgroundMethod();
                        break;
                }
            }).start();
        }

        protected static void runFirefoxBackgroundMethod() {
            Utils.threadSleep(4000, null);
            if (m_socketMutex == null) {
                System.out.println("-->Another Authentication monitoring background thread already exist.");
                return;
            }
            System.out.println("-->Firefox Windows Authentication monitoring background thread started");

            Process p;
            String file_path = "src/db/framework/authentication_popup/windows_authentication_firefox.exe";
            if (!new File("src").exists())
                file_path = "db/framework/authentication_popup/windows_authentication_firefox.exe";

            while (true) {
                try {
                    p = getRuntime().exec(file_path);
                    Utils.ProcessWatchDog pd = new Utils.ProcessWatchDog(p, 5000l, "runFirefoxBackgroundMethod()");
                    p.waitFor();  // wait for process to complete
                    pd.interrupt();
                } catch (Exception e) {
                    // ignore all errors
                }
                // wait 2 seconds
                Utils.threadSleep(2000, null);
            }
        }

        protected static void runSafariBackgroundMethod() {
            Utils.threadSleep(4000, null);
            if (m_socketMutex == null) {
                System.err.println("-->Another Authentication monitoring background thread already exist.");
                return;
            }
            System.err.println("-->Authentication monitoring background thread started");

            String p_name;
            p_name = "mac_authentication_safari.app";
            Process p;
            String file_path = "/Applications/" + p_name;
            File f = new File(file_path);
            if (!f.exists())
                file_path = "src/db/framework/authentication_popup/" + p_name;
            if (!new File("src").exists())
                file_path = "db/framework/authentication_popup/" + p_name;

            file_path = "open -n " + file_path;

            while (true) {
                try {
                    p = getRuntime().exec(file_path);
                    Utils.ProcessWatchDog pd = new Utils.ProcessWatchDog(p, 20000l, "runSafariBackgroundMethod()");
                    p.waitFor();  // wait for process to complete
                    pd.interrupt();
                } catch (Exception e) {
                    e.printStackTrace();
                    // ignore all errors
                }
                // wait 10 seconds
                Utils.threadSleep(10000, null);
            }
        }

        protected static void runChromeBackgroundMethod() {
            Utils.threadSleep(4000, null);
            if (m_socketMutex == null) {
                System.out.println("-->Another Authentication monitoring background thread already exist.");
                return;
            }
            System.out.println("-->Chrome Windows Authentication monitoring background thread started");

            Process p;
            String file_path = "src/db/framework/authentication_popup/windows_authentication_chrome.exe";
            if (!new File("src").exists())
                file_path = "db/framework/authentication_popup/windows_authentication_chrome.exe";

            // chrome need workaround for the Chrome Authentication Required popup
            // check the current URL periodically and compare it with original URL
            String curl = null;
            String org_url = MainRunner.url;
            org_url = org_url.replace("https://", "");
            org_url = org_url.replace("http://", "");
            org_url = org_url.replace("www.", "");
            int width = -1;

            while (true) {
                Utils.threadSleep(4000, null);
                curl = MainRunner.getWebDriver().getCurrentUrl();
                // current url is still the same domain, then skip
                if (curl.contains(org_url))
                    continue;

                // current url is not empty (Chrome default), then skip
                if (!(curl.contains("data:") || curl.contains("xnchegrn")))
                    continue;

                // there seems to be Chrome Authentication Required Popup
                try {
                    if (width == -1) {
                        width = MainRunner.driver.manage().window().getSize().width;
                        file_path = file_path + " " + width;
                    }
                    p = getRuntime().exec(file_path);
                    Utils.ProcessWatchDog pd = new Utils.ProcessWatchDog(p, 10000, "runChromeBackgroundMethod()");
                    p.waitFor();  // wait for process to complete
                    pd.interrupt();
                } catch (Exception e) {
                    // ignore all errors
                }
                // wait 10 seconds
                Utils.threadSleep(6000, null);
            }
        }

        public void run() {
            try {
                m_socketMutex = new ServerSocket(6999);
                m_socketMutex.accept();
            } catch (IOException e) {
                m_socketMutex = null;
            }
        }
    }
}
