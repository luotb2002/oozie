/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.oozie.action.hadoop;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.StringReader;
import java.net.ConnectException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

import org.apache.commons.io.IOUtils;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.primitives.Ints;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.filecache.DistributedCache;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.DataOutputBuffer;
import org.apache.hadoop.security.AccessControlException;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.ipc.RemoteException;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.TaskLog;
import org.apache.hadoop.mapreduce.filecache.ClientDistributedCacheManager;
import org.apache.hadoop.mapreduce.v2.util.MRApps;
import org.apache.hadoop.security.Credentials;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.security.token.TokenIdentifier;
import org.apache.hadoop.util.DiskChecker;
import org.apache.hadoop.yarn.api.ApplicationConstants;
import org.apache.hadoop.yarn.api.protocolrecords.ApplicationsRequestScope;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.ApplicationReport;
import org.apache.hadoop.yarn.api.records.ApplicationSubmissionContext;
import org.apache.hadoop.yarn.api.records.ContainerLaunchContext;
import org.apache.hadoop.yarn.api.records.FinalApplicationStatus;
import org.apache.hadoop.yarn.api.records.LocalResource;
import org.apache.hadoop.yarn.api.records.Priority;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.api.records.YarnApplicationState;
import org.apache.hadoop.yarn.client.api.YarnClient;
import org.apache.hadoop.yarn.client.api.YarnClientApplication;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.util.Apps;
import org.apache.hadoop.yarn.util.ConverterUtils;
import org.apache.hadoop.yarn.util.Records;
import org.apache.oozie.WorkflowActionBean;
import org.apache.oozie.WorkflowJobBean;
import org.apache.oozie.action.ActionExecutor;
import org.apache.oozie.action.ActionExecutorException;
import org.apache.oozie.client.OozieClient;
import org.apache.oozie.client.WorkflowAction;
import org.apache.oozie.command.coord.CoordActionStartXCommand;
import org.apache.oozie.command.wf.WorkflowXCommand;
import org.apache.oozie.service.ConfigurationService;
import org.apache.oozie.service.HadoopAccessorException;
import org.apache.oozie.service.HadoopAccessorService;
import org.apache.oozie.service.Services;
import org.apache.oozie.service.ShareLibService;
import org.apache.oozie.service.URIHandlerService;
import org.apache.oozie.service.WorkflowAppService;
import org.apache.oozie.util.ClasspathUtils;
import org.apache.oozie.util.ELEvaluationException;
import org.apache.oozie.util.ELEvaluator;
import org.apache.oozie.util.JobUtils;
import org.apache.oozie.util.LogUtils;
import org.apache.oozie.util.PropertiesUtils;
import org.apache.oozie.util.XConfiguration;
import org.apache.oozie.util.XLog;
import org.apache.oozie.util.XmlUtils;
import org.jdom.Element;
import org.jdom.JDOMException;
import org.jdom.Namespace;

import com.google.common.collect.ImmutableList;
import com.google.common.io.Closeables;


public class JavaActionExecutor extends ActionExecutor {

    public static final String RUNNING = "RUNNING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String KILLED = "KILLED";
    public static final String FAILED = "FAILED";
    public static final String FAILED_KILLED = "FAILED/KILLED";
    public static final String HADOOP_YARN_RM = "yarn.resourcemanager.address";
    public static final String HADOOP_NAME_NODE = "fs.default.name";
    public static final String OOZIE_COMMON_LIBDIR = "oozie";

    public static final String MAX_EXTERNAL_STATS_SIZE = "oozie.external.stats.max.size";
    public static final String ACL_VIEW_JOB = "mapreduce.job.acl-view-job";
    public static final String ACL_MODIFY_JOB = "mapreduce.job.acl-modify-job";
    public static final String HADOOP_YARN_TIMELINE_SERVICE_ENABLED = "yarn.timeline-service.enabled";
    public static final String HADOOP_YARN_UBER_MODE = "mapreduce.job.ubertask.enable";
    public static final String OOZIE_ACTION_LAUNCHER_PREFIX = ActionExecutor.CONF_PREFIX  + "launcher.";
    public static final String HADOOP_YARN_KILL_CHILD_JOBS_ON_AMRESTART =
            OOZIE_ACTION_LAUNCHER_PREFIX + "am.restart.kill.childjobs";
    public static final String HADOOP_MAP_MEMORY_MB = "mapreduce.map.memory.mb";
    public static final String HADOOP_CHILD_JAVA_OPTS = "mapred.child.java.opts";
    public static final String HADOOP_MAP_JAVA_OPTS = "mapreduce.map.java.opts";
    public static final String HADOOP_REDUCE_JAVA_OPTS = "mapreduce.reduce.java.opts";
    public static final String HADOOP_CHILD_JAVA_ENV = "mapred.child.env";
    public static final String HADOOP_MAP_JAVA_ENV = "mapreduce.map.env";
    public static final String HADOOP_JOB_CLASSLOADER = "mapreduce.job.classloader";
    public static final String HADOOP_USER_CLASSPATH_FIRST = "mapreduce.user.classpath.first";
    public static final String OOZIE_CREDENTIALS_SKIP = "oozie.credentials.skip";
    public static final String YARN_AM_RESOURCE_MB = "yarn.app.mapreduce.am.resource.mb";
    public static final String YARN_AM_COMMAND_OPTS = "yarn.app.mapreduce.am.command-opts";
    public static final String YARN_AM_ENV = "yarn.app.mapreduce.am.env";
    public static final int YARN_MEMORY_MB_MIN = 512;

    private static final String JAVA_MAIN_CLASS_NAME = "org.apache.oozie.action.hadoop.JavaMain";
    private static final String HADOOP_JOB_NAME = "mapred.job.name";
    private static final Set<String> DISALLOWED_PROPERTIES = new HashSet<String>();

    private static int maxActionOutputLen;
    private static int maxExternalStatsSize;
    private static int maxFSGlobMax;

    protected static final String HADOOP_USER = "user.name";

    protected XLog LOG = XLog.getLog(getClass());
    private static final String JAVA_TMP_DIR_SETTINGS = "-Djava.io.tmpdir=";

    public XConfiguration workflowConf = null;

    static {
        DISALLOWED_PROPERTIES.add(HADOOP_USER);
        DISALLOWED_PROPERTIES.add(HADOOP_NAME_NODE);
        DISALLOWED_PROPERTIES.add(HADOOP_YARN_RM);
    }

    public JavaActionExecutor() {
        this("java");
    }

    protected JavaActionExecutor(String type) {
        super(type);
    }

    public static List<Class<?>> getCommonLauncherClasses() {
        List<Class<?>> classes = new ArrayList<Class<?>>();
        classes.add(LauncherMain.class);
        classes.addAll(Services.get().get(URIHandlerService.class).getClassesForLauncher());
        classes.add(LauncherAM.class);
        classes.add(LauncherAMCallbackNotifier.class);
        return classes;
    }

    public List<Class<?>> getLauncherClasses() {
       List<Class<?>> classes = new ArrayList<Class<?>>();
        try {
            classes.add(Class.forName(JAVA_MAIN_CLASS_NAME));
        }
        catch (ClassNotFoundException e) {
            throw new RuntimeException("Class not found", e);
        }
        return classes;
    }

    @Override
    public void initActionType() {
        super.initActionType();
        maxActionOutputLen = ConfigurationService.getInt(LauncherAM.CONF_OOZIE_ACTION_MAX_OUTPUT_DATA);
        //Get the limit for the maximum allowed size of action stats
        maxExternalStatsSize = ConfigurationService.getInt(JavaActionExecutor.MAX_EXTERNAL_STATS_SIZE);
        maxExternalStatsSize = (maxExternalStatsSize == -1) ? Integer.MAX_VALUE : maxExternalStatsSize;
        //Get the limit for the maximum number of globbed files/dirs for FS operation
        maxFSGlobMax = ConfigurationService.getInt(LauncherAMUtils.CONF_OOZIE_ACTION_FS_GLOB_MAX);

        registerError(UnknownHostException.class.getName(), ActionExecutorException.ErrorType.TRANSIENT, "JA001");
        registerError(AccessControlException.class.getName(), ActionExecutorException.ErrorType.NON_TRANSIENT,
                "JA002");
        registerError(DiskChecker.DiskOutOfSpaceException.class.getName(),
                ActionExecutorException.ErrorType.NON_TRANSIENT, "JA003");
        registerError(org.apache.hadoop.hdfs.protocol.QuotaExceededException.class.getName(),
                ActionExecutorException.ErrorType.NON_TRANSIENT, "JA004");
        registerError(org.apache.hadoop.hdfs.server.namenode.SafeModeException.class.getName(),
                ActionExecutorException.ErrorType.NON_TRANSIENT, "JA005");
        registerError(ConnectException.class.getName(), ActionExecutorException.ErrorType.TRANSIENT, "  JA006");
        registerError(JDOMException.class.getName(), ActionExecutorException.ErrorType.ERROR, "JA007");
        registerError(FileNotFoundException.class.getName(), ActionExecutorException.ErrorType.ERROR, "JA008");
        registerError(IOException.class.getName(), ActionExecutorException.ErrorType.TRANSIENT, "JA009");
    }


    /**
     * Get the maximum allowed size of stats
     *
     * @return maximum size of stats
     */
    public static int getMaxExternalStatsSize() {
        return maxExternalStatsSize;
    }

    static void checkForDisallowedProps(Configuration conf, String confName) throws ActionExecutorException {
        for (String prop : DISALLOWED_PROPERTIES) {
            if (conf.get(prop) != null) {
                throw new ActionExecutorException(ActionExecutorException.ErrorType.FAILED, "JA010",
                        "Property [{0}] not allowed in action [{1}] configuration", prop, confName);
            }
        }
    }

    public Configuration createBaseHadoopConf(Context context, Element actionXml) {
        return createBaseHadoopConf(context, actionXml, true);
    }

    protected Configuration createBaseHadoopConf(Context context, Element actionXml, boolean loadResources) {

        Namespace ns = actionXml.getNamespace();
        String jobTracker = actionXml.getChild("job-tracker", ns).getTextTrim();
        String nameNode = actionXml.getChild("name-node", ns).getTextTrim();
        Configuration conf = null;
        if (loadResources) {
            conf = Services.get().get(HadoopAccessorService.class).createConfiguration(jobTracker);
        }
        else {
            conf = new Configuration(false);
        }

        conf.set(HADOOP_USER, context.getProtoActionConf().get(WorkflowAppService.HADOOP_USER));
        conf.set(HADOOP_YARN_RM, jobTracker);
        conf.set(HADOOP_NAME_NODE, nameNode);
        conf.set("mapreduce.fileoutputcommitter.marksuccessfuljobs", "true");

        return conf;
    }

    protected Configuration loadHadoopDefaultResources(Context context, Element actionXml) {
        return createBaseHadoopConf(context, actionXml);
    }

    private static void injectLauncherProperties(Configuration srcConf, Configuration launcherConf) {
        for (Map.Entry<String, String> entry : srcConf) {
            if (entry.getKey().startsWith("oozie.launcher.")) {
                String name = entry.getKey().substring("oozie.launcher.".length());
                String value = entry.getValue();
                // setting original KEY
                launcherConf.set(entry.getKey(), value);
                // setting un-prefixed key (to allow Hadoop job config
                // for the launcher job
                launcherConf.set(name, value);
            }
        }
    }

    Configuration setupLauncherConf(Configuration conf, Element actionXml, Path appPath, Context context)
            throws ActionExecutorException {
        try {
            Namespace ns = actionXml.getNamespace();
            XConfiguration launcherConf = new XConfiguration();
            // Inject action defaults for launcher
            HadoopAccessorService has = Services.get().get(HadoopAccessorService.class);
            XConfiguration actionDefaultConf = has.createActionDefaultConf(conf.get(HADOOP_YARN_RM), getType());
            injectLauncherProperties(actionDefaultConf, launcherConf);
            // Inject <job-xml> and <configuration> for launcher
            try {
                parseJobXmlAndConfiguration(context, actionXml, appPath, launcherConf, true);
            } catch (HadoopAccessorException ex) {
                throw convertException(ex);
            } catch (URISyntaxException ex) {
                throw convertException(ex);
            }
            XConfiguration.copy(launcherConf, conf);
            checkForDisallowedProps(launcherConf, "launcher configuration");
            return conf;
        }
        catch (IOException ex) {
            throw convertException(ex);
        }
    }

    void injectLauncherTimelineServiceEnabled(Configuration launcherConf, Configuration actionConf) {
        // Getting delegation token for ATS. If tez-site.xml is present in distributed cache, turn on timeline service.
        if (actionConf.get("oozie.launcher." + HADOOP_YARN_TIMELINE_SERVICE_ENABLED) == null
                && ConfigurationService.getBoolean(OOZIE_ACTION_LAUNCHER_PREFIX + HADOOP_YARN_TIMELINE_SERVICE_ENABLED)) {
            String cacheFiles = launcherConf.get("mapred.cache.files");
            if (cacheFiles != null && cacheFiles.contains("tez-site.xml")) {
                launcherConf.setBoolean(HADOOP_YARN_TIMELINE_SERVICE_ENABLED, true);
            }
        }
    }

    public static void parseJobXmlAndConfiguration(Context context, Element element, Path appPath, Configuration conf)
            throws IOException, ActionExecutorException, HadoopAccessorException, URISyntaxException {
        parseJobXmlAndConfiguration(context, element, appPath, conf, false);
    }

    public static void parseJobXmlAndConfiguration(Context context, Element element, Path appPath, Configuration conf,
            boolean isLauncher) throws IOException, ActionExecutorException, HadoopAccessorException, URISyntaxException {
        Namespace ns = element.getNamespace();
        @SuppressWarnings("unchecked")
        Iterator<Element> it = element.getChildren("job-xml", ns).iterator();
        HashMap<String, FileSystem> filesystemsMap = new HashMap<String, FileSystem>();
        HadoopAccessorService has = Services.get().get(HadoopAccessorService.class);
        while (it.hasNext()) {
            Element e = it.next();
            String jobXml = e.getTextTrim();
            Path pathSpecified = new Path(jobXml);
            Path path = pathSpecified.isAbsolute() ? pathSpecified : new Path(appPath, jobXml);
            FileSystem fs;
            if (filesystemsMap.containsKey(path.toUri().getAuthority())) {
              fs = filesystemsMap.get(path.toUri().getAuthority());
            }
            else {
              if (path.toUri().getAuthority() != null) {
                fs = has.createFileSystem(context.getWorkflow().getUser(), path.toUri(),
                        has.createConfiguration(path.toUri().getAuthority()));
              }
              else {
                fs = context.getAppFileSystem();
              }
              filesystemsMap.put(path.toUri().getAuthority(), fs);
            }
            Configuration jobXmlConf = new XConfiguration(fs.open(path));
            try {
                String jobXmlConfString = XmlUtils.prettyPrint(jobXmlConf).toString();
                jobXmlConfString = XmlUtils.removeComments(jobXmlConfString);
                jobXmlConfString = context.getELEvaluator().evaluate(jobXmlConfString, String.class);
                jobXmlConf = new XConfiguration(new StringReader(jobXmlConfString));
            }
            catch (ELEvaluationException ex) {
                throw new ActionExecutorException(ActionExecutorException.ErrorType.TRANSIENT, "EL_EVAL_ERROR", ex
                        .getMessage(), ex);
            }
            catch (Exception ex) {
                context.setErrorInfo("EL_ERROR", ex.getMessage());
            }
            checkForDisallowedProps(jobXmlConf, "job-xml");
            if (isLauncher) {
                injectLauncherProperties(jobXmlConf, conf);
            } else {
                XConfiguration.copy(jobXmlConf, conf);
            }
        }
        Element e = element.getChild("configuration", ns);
        if (e != null) {
            String strConf = XmlUtils.prettyPrint(e).toString();
            XConfiguration inlineConf = new XConfiguration(new StringReader(strConf));
            checkForDisallowedProps(inlineConf, "inline configuration");
            if (isLauncher) {
                injectLauncherProperties(inlineConf, conf);
            } else {
                XConfiguration.copy(inlineConf, conf);
            }
        }
    }

    Configuration setupActionConf(Configuration actionConf, Context context, Element actionXml, Path appPath)
            throws ActionExecutorException {
        try {
            HadoopAccessorService has = Services.get().get(HadoopAccessorService.class);
            XConfiguration actionDefaults = has.createActionDefaultConf(actionConf.get(HADOOP_YARN_RM), getType());
            XConfiguration.injectDefaults(actionDefaults, actionConf);
            has.checkSupportedFilesystem(appPath.toUri());

            // Set the Java Main Class for the Java action to give to the Java launcher
            setJavaMain(actionConf, actionXml);

            parseJobXmlAndConfiguration(context, actionXml, appPath, actionConf);

            // set cancel.delegation.token in actionConf that child job doesn't cancel delegation token
            actionConf.setBoolean("mapreduce.job.complete.cancel.delegation.tokens", false);
            setRootLoggerLevel(actionConf);
            return actionConf;
        }
        catch (IOException ex) {
            throw convertException(ex);
        }
        catch (HadoopAccessorException ex) {
            throw convertException(ex);
        }
        catch (URISyntaxException ex) {
            throw convertException(ex);
        }
    }

    /**
     * Set root log level property in actionConf
     * @param actionConf
     */
    void setRootLoggerLevel(Configuration actionConf) {
        String oozieActionTypeRootLogger = "oozie.action." + getType() + LauncherAMUtils.ROOT_LOGGER_LEVEL;
        String oozieActionRootLogger = "oozie.action." + LauncherAMUtils.ROOT_LOGGER_LEVEL;

        // check if root log level has already mentioned in action configuration
        String rootLogLevel = actionConf.get(oozieActionTypeRootLogger, actionConf.get(oozieActionRootLogger));
        if (rootLogLevel != null) {
            // root log level is mentioned in action configuration
            return;
        }

        // set the root log level which is mentioned in oozie default
        rootLogLevel = ConfigurationService.get(oozieActionTypeRootLogger);
        if (rootLogLevel != null && rootLogLevel.length() > 0) {
            actionConf.set(oozieActionRootLogger, rootLogLevel);
        }
        else {
            rootLogLevel = ConfigurationService.get(oozieActionRootLogger);
            if (rootLogLevel != null && rootLogLevel.length() > 0) {
                actionConf.set(oozieActionRootLogger, rootLogLevel);
            }
        }
    }

    Configuration addToCache(Configuration conf, Path appPath, String filePath, boolean archive)
            throws ActionExecutorException {

        URI uri = null;
        try {
            uri = new URI(getTrimmedEncodedPath(filePath));
            URI baseUri = appPath.toUri();
            if (uri.getScheme() == null) {
                String resolvedPath = uri.getPath();
                if (!resolvedPath.startsWith("/")) {
                    resolvedPath = baseUri.getPath() + "/" + resolvedPath;
                }
                uri = new URI(baseUri.getScheme(), baseUri.getAuthority(), resolvedPath, uri.getQuery(), uri.getFragment());
            }
            if (archive) {
                DistributedCache.addCacheArchive(uri.normalize(), conf);
            }
            else {
                String fileName = filePath.substring(filePath.lastIndexOf("/") + 1);
                if (fileName.endsWith(".so") || fileName.contains(".so.")) { // .so files
                    uri = new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), uri.getQuery(), fileName);
                    DistributedCache.addCacheFile(uri.normalize(), conf);
                }
                else if (fileName.endsWith(".jar")) { // .jar files
                    if (!fileName.contains("#")) {
                        String user = conf.get("user.name");
                        Path pathToAdd = new Path(uri.normalize());
                        Services.get().get(HadoopAccessorService.class).addFileToClassPath(user, pathToAdd, conf);
                    }
                    else {
                        DistributedCache.addCacheFile(uri.normalize(), conf);
                    }
                }
                else { // regular files
                    if (!fileName.contains("#")) {
                        uri = new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), uri.getQuery(), fileName);
                    }
                    DistributedCache.addCacheFile(uri.normalize(), conf);
                }
            }
            DistributedCache.createSymlink(conf);
            return conf;
        }
        catch (Exception ex) {
            LOG.debug(
                    "Errors when add to DistributedCache. Path=" + Objects.toString(uri, "<null>") + ", archive="
                            + archive + ", conf=" + XmlUtils.prettyPrint(conf).toString());
            throw convertException(ex);
        }
    }

    public void prepareActionDir(FileSystem actionFs, Context context) throws ActionExecutorException {
        try {
            Path actionDir = context.getActionDir();
            Path tempActionDir = new Path(actionDir.getParent(), actionDir.getName() + ".tmp");
            if (!actionFs.exists(actionDir)) {
                try {
                    actionFs.mkdirs(tempActionDir);
                    actionFs.rename(tempActionDir, actionDir);
                }
                catch (IOException ex) {
                    actionFs.delete(tempActionDir, true);
                    actionFs.delete(actionDir, true);
                    throw ex;
                }
            }
        }
        catch (Exception ex) {
            throw convertException(ex);
        }
    }

    void cleanUpActionDir(FileSystem actionFs, Context context) throws ActionExecutorException {
        try {
            Path actionDir = context.getActionDir();
            if (!context.getProtoActionConf().getBoolean(WorkflowXCommand.KEEP_WF_ACTION_DIR, false)
                    && actionFs.exists(actionDir)) {
                actionFs.delete(actionDir, true);
            }
        }
        catch (Exception ex) {
            throw convertException(ex);
        }
    }

    protected void addShareLib(Configuration conf, String[] actionShareLibNames)
            throws ActionExecutorException {
        Set<String> confSet = new HashSet<String>(Arrays.asList(getShareLibFilesForActionConf() == null ? new String[0]
                : getShareLibFilesForActionConf()));

        Set<Path> sharelibList = new HashSet<Path>();

        if (actionShareLibNames != null) {
            try {
                ShareLibService shareLibService = Services.get().get(ShareLibService.class);
                FileSystem fs = shareLibService.getFileSystem();
                if (fs != null) {
                    for (String actionShareLibName : actionShareLibNames) {
                        List<Path> listOfPaths = shareLibService.getShareLibJars(actionShareLibName);
                        if (listOfPaths != null && !listOfPaths.isEmpty()) {
                            for (Path actionLibPath : listOfPaths) {
                                String fragmentName = new URI(actionLibPath.toString()).getFragment();
                                String fileName = fragmentName == null ? actionLibPath.getName() : fragmentName;
                                if (confSet.contains(fileName)) {
                                    Configuration jobXmlConf = shareLibService.getShareLibConf(actionShareLibName,
                                            actionLibPath);
                                    if (jobXmlConf != null) {
                                        checkForDisallowedProps(jobXmlConf, actionLibPath.getName());
                                        XConfiguration.injectDefaults(jobXmlConf, conf);
                                        LOG.trace("Adding properties of " + actionLibPath + " to job conf");
                                    }
                                }
                                else {
                                    // Filtering out duplicate jars or files
                                    sharelibList.add(new Path(actionLibPath.toUri()) {
                                        @Override
                                        public int hashCode() {
                                            return getName().hashCode();
                                        }
                                        @Override
                                        public String getName() {
                                            try {
                                                return (new URI(toString())).getFragment() == null ? new Path(toUri()).getName()
                                                        : (new URI(toString())).getFragment();
                                            }
                                            catch (URISyntaxException e) {
                                                throw new RuntimeException(e);
                                            }
                                        }
                                        @Override
                                        public boolean equals(Object input) {
                                            if (input == null) {
                                                return false;
                                            }
                                            if (input == this) {
                                                return true;
                                            }
                                            if (!(input instanceof Path)) {
                                                return false;
                                            }
                                            return getName().equals(((Path) input).getName());
                                        }
                                    });
                                }
                            }
                        }
                    }
                }
                for (Path libPath : sharelibList) {
                    addToCache(conf, libPath, libPath.toUri().getPath(), false);
                }
            }
            catch (URISyntaxException ex) {
                throw new ActionExecutorException(ActionExecutorException.ErrorType.FAILED, "Error configuring sharelib",
                        ex.getMessage());
            }
            catch (IOException ex) {
                throw new ActionExecutorException(ActionExecutorException.ErrorType.FAILED, "It should never happen",
                        ex.getMessage());
            }
        }
    }

    protected void addSystemShareLibForAction(Configuration conf) throws ActionExecutorException {
        ShareLibService shareLibService = Services.get().get(ShareLibService.class);
        // ShareLibService is null for test cases
        if (shareLibService != null) {
            try {
                List<Path> listOfPaths = shareLibService.getSystemLibJars(JavaActionExecutor.OOZIE_COMMON_LIBDIR);
                if (listOfPaths.isEmpty()) {
                    throw new ActionExecutorException(ActionExecutorException.ErrorType.FAILED, "EJ001",
                            "Could not locate Oozie sharelib");
                }
                FileSystem fs = listOfPaths.get(0).getFileSystem(conf);
                for (Path actionLibPath : listOfPaths) {
                    JobUtils.addFileToClassPath(actionLibPath, conf, fs);
                    DistributedCache.createSymlink(conf);
                }
                listOfPaths = shareLibService.getSystemLibJars(getType());
                if (!listOfPaths.isEmpty()) {
                    for (Path actionLibPath : listOfPaths) {
                        JobUtils.addFileToClassPath(actionLibPath, conf, fs);
                        DistributedCache.createSymlink(conf);
                    }
                }
            }
            catch (IOException ex) {
                throw new ActionExecutorException(ActionExecutorException.ErrorType.FAILED, "It should never happen",
                        ex.getMessage());
            }
        }
    }

    protected void addActionLibs(Path appPath, Configuration conf) throws ActionExecutorException {
        String[] actionLibsStrArr = conf.getStrings("oozie.launcher.oozie.libpath");
        if (actionLibsStrArr != null) {
            try {
                for (String actionLibsStr : actionLibsStrArr) {
                    actionLibsStr = actionLibsStr.trim();
                    if (actionLibsStr.length() > 0)
                    {
                        Path actionLibsPath = new Path(actionLibsStr);
                        String user = conf.get("user.name");
                        FileSystem fs = Services.get().get(HadoopAccessorService.class).createFileSystem(user, appPath.toUri(), conf);
                        if (fs.exists(actionLibsPath)) {
                            FileStatus[] files = fs.listStatus(actionLibsPath);
                            for (FileStatus file : files) {
                                addToCache(conf, appPath, file.getPath().toUri().getPath(), false);
                            }
                        }
                    }
                }
            }
            catch (HadoopAccessorException ex){
                throw new ActionExecutorException(ActionExecutorException.ErrorType.FAILED,
                        ex.getErrorCode().toString(), ex.getMessage());
            }
            catch (IOException ex){
                throw new ActionExecutorException(ActionExecutorException.ErrorType.FAILED,
                        "It should never happen", ex.getMessage());
            }
        }
    }

    @SuppressWarnings("unchecked")
    public void setLibFilesArchives(Context context, Element actionXml, Path appPath, Configuration conf)
            throws ActionExecutorException {
        Configuration proto = context.getProtoActionConf();

        // Workflow lib/
        String[] paths = proto.getStrings(WorkflowAppService.APP_LIB_PATH_LIST);
        if (paths != null) {
            for (String path : paths) {
                addToCache(conf, appPath, path, false);
            }
        }

        // Action libs
        addActionLibs(appPath, conf);

        // files and archives defined in the action
        for (Element eProp : (List<Element>) actionXml.getChildren()) {
            if (eProp.getName().equals("file")) {
                String[] filePaths = eProp.getTextTrim().split(",");
                for (String path : filePaths) {
                    addToCache(conf, appPath, path, false);
                }
            }
            else if (eProp.getName().equals("archive")) {
                String[] archivePaths = eProp.getTextTrim().split(",");
                for (String path : archivePaths){
                    addToCache(conf, appPath, path.trim(), true);
                }
            }
        }

        addAllShareLibs(appPath, conf, context, actionXml);
    }

    @VisibleForTesting
    protected static String getTrimmedEncodedPath(String path) {
        return path.trim().replace(" ", "%20");
    }

    // Adds action specific share libs and common share libs
    private void addAllShareLibs(Path appPath, Configuration conf, Context context, Element actionXml)
            throws ActionExecutorException {
        // Add action specific share libs
        addActionShareLib(appPath, conf, context, actionXml);
        // Add common sharelibs for Oozie and launcher jars
        addSystemShareLibForAction(conf);
    }

    private void addActionShareLib(Path appPath, Configuration conf, Context context, Element actionXml)
            throws ActionExecutorException {
        XConfiguration wfJobConf = null;
        try {
            wfJobConf = getWorkflowConf(context);
        }
        catch (IOException ioe) {
            throw new ActionExecutorException(ActionExecutorException.ErrorType.FAILED, "It should never happen",
                    ioe.getMessage());
        }
        // Action sharelibs are only added if user has specified to use system libpath
        if (conf.get(OozieClient.USE_SYSTEM_LIBPATH) == null) {
            if (wfJobConf.getBoolean(OozieClient.USE_SYSTEM_LIBPATH,
                    ConfigurationService.getBoolean(OozieClient.USE_SYSTEM_LIBPATH))) {
                // add action specific sharelibs
                addShareLib(conf, getShareLibNames(context, actionXml, conf));
            }
        }
        else {
            if (conf.getBoolean(OozieClient.USE_SYSTEM_LIBPATH, false)) {
                // add action specific sharelibs
                addShareLib(conf, getShareLibNames(context, actionXml, conf));
            }
        }
    }


    protected String getLauncherMain(Configuration launcherConf, Element actionXml) {
        return launcherConf.get(LauncherAM.CONF_OOZIE_ACTION_MAIN_CLASS, JavaMain.class.getName());
    }

    private void setJavaMain(Configuration actionConf, Element actionXml) {
        Namespace ns = actionXml.getNamespace();
        Element e = actionXml.getChild("main-class", ns);
        if (e != null) {
            actionConf.set(JavaMain.JAVA_MAIN_CLASS, e.getTextTrim());
        }
    }

    private static final String QUEUE_NAME = "mapred.job.queue.name";

    private static final Set<String> SPECIAL_PROPERTIES = new HashSet<String>();

    static {
        SPECIAL_PROPERTIES.add(QUEUE_NAME);
        SPECIAL_PROPERTIES.add(ACL_VIEW_JOB);
        SPECIAL_PROPERTIES.add(ACL_MODIFY_JOB);
    }

    @SuppressWarnings("unchecked")
    Configuration createLauncherConf(FileSystem actionFs, Context context, WorkflowAction action, Element actionXml,
            Configuration actionConf) throws ActionExecutorException {
        try {

            // app path could be a file
            Path appPathRoot = new Path(context.getWorkflow().getAppPath());
            if (actionFs.isFile(appPathRoot)) {
                appPathRoot = appPathRoot.getParent();
            }

            // launcher job configuration
            Configuration launcherJobConf = createBaseHadoopConf(context, actionXml);
            // cancel delegation token on a launcher job which stays alive till child job(s) finishes
            // otherwise (in mapred action), doesn't cancel not to disturb running child job
            launcherJobConf.setBoolean("mapreduce.job.complete.cancel.delegation.tokens", true);
            setupLauncherConf(launcherJobConf, actionXml, appPathRoot, context);

            // Properties for when a launcher job's AM gets restarted
            if (ConfigurationService.getBoolean(HADOOP_YARN_KILL_CHILD_JOBS_ON_AMRESTART)) {
                // launcher time filter is required to prune the search of launcher tag.
                // Setting coordinator action nominal time as launcher time as it child job cannot launch before nominal
                // time. Workflow created time is good enough when workflow is running independently or workflow is
                // rerunning from failed node.
                long launcherTime = System.currentTimeMillis();
                String coordActionNominalTime = context.getProtoActionConf().get(
                        CoordActionStartXCommand.OOZIE_COORD_ACTION_NOMINAL_TIME);
                if (coordActionNominalTime != null) {
                    launcherTime = Long.parseLong(coordActionNominalTime);
                }
                else if (context.getWorkflow().getCreatedTime() != null) {
                    launcherTime = context.getWorkflow().getCreatedTime().getTime();
                }
                String actionYarnTag = getActionYarnTag(getWorkflowConf(context), context.getWorkflow(), action);
                LauncherHelper.setupYarnRestartHandling(launcherJobConf, actionConf, actionYarnTag, launcherTime);
            }
            else {
                LOG.info(MessageFormat.format("{0} is set to false, not setting YARN restart properties",
                        HADOOP_YARN_KILL_CHILD_JOBS_ON_AMRESTART));
            }

            String actionShareLibProperty = actionConf.get(ACTION_SHARELIB_FOR + getType());
            if (actionShareLibProperty != null) {
                launcherJobConf.set(ACTION_SHARELIB_FOR + getType(), actionShareLibProperty);
            }
            setLibFilesArchives(context, actionXml, appPathRoot, launcherJobConf);

            // Inject Oozie job information if enabled.
            injectJobInfo(launcherJobConf, actionConf, context, action);

            injectLauncherCallback(context, launcherJobConf);

            String jobId = context.getWorkflow().getId();
            String actionId = action.getId();
            Path actionDir = context.getActionDir();
            String recoveryId = context.getRecoveryId();

            // Getting the prepare XML from the action XML
            Namespace ns = actionXml.getNamespace();
            Element prepareElement = actionXml.getChild("prepare", ns);
            String prepareXML = "";
            if (prepareElement != null) {
                if (prepareElement.getChildren().size() > 0) {
                    prepareXML = XmlUtils.prettyPrint(prepareElement).toString().trim();
                }
            }
            LauncherHelper.setupLauncherInfo(launcherJobConf, jobId, actionId, actionDir, recoveryId, actionConf,
                    prepareXML);

            // Set the launcher Main Class
            LauncherHelper.setupMainClass(launcherJobConf, getLauncherMain(launcherJobConf, actionXml));
            LauncherHelper.setupLauncherURIHandlerConf(launcherJobConf);
            LauncherHelper.setupMaxOutputData(launcherJobConf, getMaxOutputData(actionConf));
            LauncherHelper.setupMaxExternalStatsSize(launcherJobConf, maxExternalStatsSize);
            LauncherHelper.setupMaxFSGlob(launcherJobConf, maxFSGlobMax);

            List<Element> list = actionXml.getChildren("arg", ns);
            String[] args = new String[list.size()];
            for (int i = 0; i < list.size(); i++) {
                args[i] = list.get(i).getTextTrim();
            }
            LauncherHelper.setupMainArguments(launcherJobConf, args);
            // backward compatibility flag - see OOZIE-2872
            boolean nullArgsAllowed = ConfigurationService.getBoolean(LauncherAMUtils.CONF_OOZIE_NULL_ARGS_ALLOWED);
            launcherJobConf.setBoolean(LauncherAMUtils.CONF_OOZIE_NULL_ARGS_ALLOWED, nullArgsAllowed);

            // Make mapred.child.java.opts and mapreduce.map.java.opts equal, but give values from the latter priority; also append
            // <java-opt> and <java-opts> and give those highest priority
            StringBuilder opts = new StringBuilder(launcherJobConf.get(HADOOP_CHILD_JAVA_OPTS, ""));
            if (launcherJobConf.get(HADOOP_MAP_JAVA_OPTS) != null) {
                opts.append(" ").append(launcherJobConf.get(HADOOP_MAP_JAVA_OPTS));
            }
            List<Element> javaopts = actionXml.getChildren("java-opt", ns);
            for (Element opt: javaopts) {
                opts.append(" ").append(opt.getTextTrim());
            }
            Element opt = actionXml.getChild("java-opts", ns);
            if (opt != null) {
                opts.append(" ").append(opt.getTextTrim());
            }
            launcherJobConf.set(HADOOP_CHILD_JAVA_OPTS, opts.toString().trim());
            launcherJobConf.set(HADOOP_MAP_JAVA_OPTS, opts.toString().trim());

            injectLauncherTimelineServiceEnabled(launcherJobConf, actionConf);

            // properties from action that are needed by the launcher (e.g. QUEUE NAME, ACLs)
            // maybe we should add queue to the WF schema, below job-tracker
            actionConfToLauncherConf(actionConf, launcherJobConf);

            return launcherJobConf;
        }
        catch (Exception ex) {
            throw convertException(ex);
        }
    }

    @VisibleForTesting
    protected static int getMaxOutputData(Configuration actionConf) {
        String userMaxActionOutputLen = actionConf.get("oozie.action.max.output.data");
        if (userMaxActionOutputLen != null) {
            Integer i = Ints.tryParse(userMaxActionOutputLen);
            return i != null ? i : maxActionOutputLen;
        }
        return maxActionOutputLen;
    }

    protected void injectCallback(Context context, Configuration conf) {
        String callback = context.getCallbackUrl(LauncherAMCallbackNotifier.OOZIE_LAUNCHER_CALLBACK_JOBSTATUS_TOKEN);
        conf.set(LauncherAMCallbackNotifier.OOZIE_LAUNCHER_CALLBACK_URL, callback);
    }

    void injectActionCallback(Context context, Configuration actionConf) {
        // action callback needs to be injected only for mapreduce actions.
    }

    void injectLauncherCallback(Context context, Configuration launcherConf) {
        injectCallback(context, launcherConf);
    }

    private void actionConfToLauncherConf(Configuration actionConf, Configuration launcherConf) {
        for (String name : SPECIAL_PROPERTIES) {
            if (actionConf.get(name) != null && launcherConf.get("oozie.launcher." + name) == null) {
                launcherConf.set(name, actionConf.get(name));
            }
        }
    }

    public void submitLauncher(FileSystem actionFs, final Context context, WorkflowAction action) throws ActionExecutorException {
        YarnClient yarnClient = null;
        try {
            Path appPathRoot = new Path(context.getWorkflow().getAppPath());

            // app path could be a file
            if (actionFs.isFile(appPathRoot)) {
                appPathRoot = appPathRoot.getParent();
            }

            Element actionXml = XmlUtils.parseXml(action.getConf());

            // action job configuration
            Configuration actionConf = loadHadoopDefaultResources(context, actionXml);
            setupActionConf(actionConf, context, actionXml, appPathRoot);
            LOG.debug("Setting LibFilesArchives ");
            setLibFilesArchives(context, actionXml, appPathRoot, actionConf);

            injectActionCallback(context, actionConf);

            if(actionConf.get(ACL_MODIFY_JOB) == null || actionConf.get(ACL_MODIFY_JOB).trim().equals("")) {
                // ONLY in the case where user has not given the
                // modify-job ACL specifically
                if (context.getWorkflow().getAcl() != null) {
                    // setting the group owning the Oozie job to allow anybody in that
                    // group to modify the jobs.
                    actionConf.set(ACL_MODIFY_JOB, context.getWorkflow().getAcl());
                }
            }

            // Setting the credential properties in launcher conf
            Configuration credentialsConf = null;

            HashMap<String, CredentialsProperties> credentialsProperties = setCredentialPropertyToActionConf(context,
                    action, actionConf);
            Credentials credentials = null;
            if (credentialsProperties != null) {
                credentials = new Credentials();
                // Adding if action need to set more credential tokens
                credentialsConf = new Configuration(false);
                XConfiguration.copy(actionConf, credentialsConf);
                setCredentialTokens(credentials, credentialsConf, context, action, credentialsProperties);

                // insert conf to action conf from credentialsConf
                for (Entry<String, String> entry : credentialsConf) {
                    if (actionConf.get(entry.getKey()) == null) {
                        actionConf.set(entry.getKey(), entry.getValue());
                    }
                }
            }
            Configuration launcherJobConf = createLauncherConf(actionFs, context, action, actionXml, actionConf);

            String consoleUrl;
            String launcherId = LauncherHelper.getRecoveryId(launcherJobConf, context.getActionDir(), context
                    .getRecoveryId());
            boolean alreadyRunning = launcherId != null;

            // if user-retry is on, always submit new launcher
            boolean isUserRetry = ((WorkflowActionBean)action).isUserRetry();
            LOG.debug("Creating yarnClient for action {0}", action.getId());
            yarnClient = createYarnClient(context, launcherJobConf);

            if (alreadyRunning && !isUserRetry) {
                try {
                    ApplicationId appId = ConverterUtils.toApplicationId(launcherId);
                    ApplicationReport report = yarnClient.getApplicationReport(appId);
                    consoleUrl = report.getTrackingUrl();
                } catch (RemoteException e) {
                    // caught when the application id does not exist
                    LOG.error("Got RemoteException from YARN", e);
                    String jobTracker = launcherJobConf.get(HADOOP_YARN_RM);
                    throw new ActionExecutorException(ActionExecutorException.ErrorType.ERROR, "JA017",
                            "unknown job [{0}@{1}], cannot recover", launcherId, jobTracker);
                }
            }
            else {
                // TODO: OYA: do we actually need an MR token?  IIRC, it's issued by the JHS
//                // setting up propagation of the delegation token.
//                Token<DelegationTokenIdentifier> mrdt = null;
//                HadoopAccessorService has = Services.get().get(HadoopAccessorService.class);
//                mrdt = jobClient.getDelegationToken(has
//                        .getMRDelegationTokenRenewer(launcherJobConf));
//                launcherJobConf.getCredentials().addToken(HadoopAccessorService.MR_TOKEN_ALIAS, mrdt);

                // insert credentials tokens to launcher job conf if needed
                if (credentialsConf != null) {
                    for (Token<? extends TokenIdentifier> tk :credentials.getAllTokens()) {
                        Text fauxAlias = new Text(tk.getKind() + "_" + tk.getService());
                        LOG.debug("ADDING TOKEN: " + fauxAlias);
                        credentials.addToken(fauxAlias, tk);
                    }
                    if (credentials.numberOfSecretKeys() > 0) {
                        for (Entry<String, CredentialsProperties> entry : credentialsProperties.entrySet()) {
                            CredentialsProperties credProps = entry.getValue();
                            if (credProps != null) {
                                Text credName = new Text(credProps.getName());
                                byte[] secKey = credentials.getSecretKey(credName);
                                if (secKey != null) {
                                    LOG.debug("ADDING CREDENTIAL: " + credProps.getName());
                                    credentials.addSecretKey(credName, secKey);
                                }
                            }
                        }
                    }
                }
                else {
                    LOG.info("No need to inject credentials.");
                }

                String user = context.getWorkflow().getUser();

                YarnClientApplication newApp = yarnClient.createApplication();
                ApplicationId appId = newApp.getNewApplicationResponse().getApplicationId();
                ApplicationSubmissionContext appContext =
                        createAppSubmissionContext(appId, launcherJobConf, user, context, actionConf, action.getName(),
                                credentials);
                yarnClient.submitApplication(appContext);

                launcherId = appId.toString();
                LOG.debug("After submission get the launcherId [{0}]", launcherId);
                ApplicationReport appReport = yarnClient.getApplicationReport(appId);
                consoleUrl = appReport.getTrackingUrl();
            }

            String jobTracker = launcherJobConf.get(HADOOP_YARN_RM);
            context.setStartData(launcherId, jobTracker, consoleUrl);
        }
        catch (Exception ex) {
            throw convertException(ex);
        }
        finally {
            if (yarnClient != null) {
                Closeables.closeQuietly(yarnClient);
            }
        }
    }

    private ApplicationSubmissionContext createAppSubmissionContext(ApplicationId appId, Configuration launcherJobConf,
                                        String user, Context context, Configuration actionConf, String actionName,
                                        Credentials credentials)
            throws IOException, HadoopAccessorException, URISyntaxException {

        ApplicationSubmissionContext appContext = Records.newRecord(ApplicationSubmissionContext.class);

        String jobName = XLog.format(
                "oozie:launcher:T={0}:W={1}:A={2}:ID={3}", getType(),
                context.getWorkflow().getAppName(), actionName,
                context.getWorkflow().getId());

        appContext.setApplicationId(appId);
        appContext.setApplicationName(jobName);
        appContext.setApplicationType("Oozie Launcher");
        Priority pri = Records.newRecord(Priority.class);
        int priority = 0; // TODO: OYA: Add a constant or a config
        pri.setPriority(priority);
        appContext.setPriority(pri);
        appContext.setQueue("default");  // TODO: will be possible to set in <launcher>
        ContainerLaunchContext amContainer = Records.newRecord(ContainerLaunchContext.class);

        // Set the resources to localize
        Map<String, LocalResource> localResources = new HashMap<String, LocalResource>();
        ClientDistributedCacheManager.determineTimestampsAndCacheVisibilities(launcherJobConf);
        MRApps.setupDistributedCache(launcherJobConf, localResources);
        // Add the Launcher and Action configs as Resources
        HadoopAccessorService has = Services.get().get(HadoopAccessorService.class);
        LocalResource launcherJobConfLR = has.createLocalResourceForConfigurationFile(LauncherAM.LAUNCHER_JOB_CONF_XML, user,
                launcherJobConf, context.getAppFileSystem().getUri(), context.getActionDir());
        localResources.put(LauncherAM.LAUNCHER_JOB_CONF_XML, launcherJobConfLR);
        LocalResource actionConfLR = has.createLocalResourceForConfigurationFile(LauncherAM.ACTION_CONF_XML, user, actionConf,
                context.getAppFileSystem().getUri(), context.getActionDir());
        localResources.put(LauncherAM.ACTION_CONF_XML, actionConfLR);
        amContainer.setLocalResources(localResources);

        // Set the environment variables
        Map<String, String> env = new HashMap<String, String>();
        // This adds the Hadoop jars to the classpath in the Launcher JVM
        ClasspathUtils.setupClasspath(env, launcherJobConf);

        if (needToAddMapReduceToClassPath()) {
            ClasspathUtils.addMapReduceToClasspath(env, launcherJobConf);
        }

        addActionSpecificEnvVars(env);
        amContainer.setEnvironment(Collections.unmodifiableMap(env));

        // Set the command
        List<String> vargs = new ArrayList<String>(6);
        vargs.add(Apps.crossPlatformify(ApplicationConstants.Environment.JAVA_HOME.toString())
                + "/bin/java");

        vargs.add("-Dlog4j.configuration=container-log4j.properties");
        vargs.add("-Dlog4j.debug=true");
        vargs.add("-D" + YarnConfiguration.YARN_APP_CONTAINER_LOG_DIR + "=" + ApplicationConstants.LOG_DIR_EXPANSION_VAR);
        vargs.add("-D" + YarnConfiguration.YARN_APP_CONTAINER_LOG_SIZE + "=" + 0);
        vargs.add("-Dhadoop.root.logger=INFO,CLA");
        vargs.add("-Dhadoop.root.logfile=" + TaskLog.LogName.SYSLOG);
        vargs.add("-Dsubmitter.user=" + context.getWorkflow().getUser());

        Path amTmpDir = new Path(Apps.crossPlatformify(ApplicationConstants.Environment.PWD.toString()),
                YarnConfiguration.DEFAULT_CONTAINER_TEMP_DIR);
        vargs.add("-Djava.io.tmpdir=" + amTmpDir);

        vargs.add(LauncherAM.class.getCanonicalName());
        vargs.add("1>" + ApplicationConstants.LOG_DIR_EXPANSION_VAR +
                Path.SEPARATOR + ApplicationConstants.STDOUT);
        vargs.add("2>" + ApplicationConstants.LOG_DIR_EXPANSION_VAR +
                Path.SEPARATOR + ApplicationConstants.STDERR);
        StringBuilder mergedCommand = new StringBuilder();
        for (CharSequence str : vargs) {
            mergedCommand.append(str).append(" ");
        }

        List<String> vargsFinal = ImmutableList.of(mergedCommand.toString());
        LOG.debug("Command to launch container for ApplicationMaster is: {0}", mergedCommand);
        amContainer.setCommands(vargsFinal);
        appContext.setAMContainerSpec(amContainer);

        // Set tokens
        if (credentials != null) {
            DataOutputBuffer dob = new DataOutputBuffer();
            credentials.writeTokenStorageToStream(dob);
            amContainer.setTokens(ByteBuffer.wrap(dob.getData(), 0, dob.getLength()));
        }

        // Set Resources
        // TODO: OYA: make resources allocated for the AM configurable and choose good defaults (memory MB, vcores)
        Resource resource = Resource.newInstance(2048, 1);
        appContext.setResource(resource);
        appContext.setCancelTokensWhenComplete(true);

        return appContext;
    }

    protected HashMap<String, CredentialsProperties> setCredentialPropertyToActionConf(Context context,
            WorkflowAction action, Configuration actionConf) throws Exception {
        HashMap<String, CredentialsProperties> credPropertiesMap = null;
        if (context != null && action != null) {
            if (!"true".equals(actionConf.get(OOZIE_CREDENTIALS_SKIP))) {
                XConfiguration wfJobConf = getWorkflowConf(context);
                if ("false".equals(actionConf.get(OOZIE_CREDENTIALS_SKIP)) ||
                    !wfJobConf.getBoolean(OOZIE_CREDENTIALS_SKIP, ConfigurationService.getBoolean(OOZIE_CREDENTIALS_SKIP))) {
                    credPropertiesMap = getActionCredentialsProperties(context, action);
                    if (!credPropertiesMap.isEmpty()) {
                        for (Entry<String, CredentialsProperties> entry : credPropertiesMap.entrySet()) {
                            if (entry.getValue() != null) {
                                CredentialsProperties prop = entry.getValue();
                                LOG.debug("Credential Properties set for action : " + action.getId());
                                for (Entry<String, String> propEntry : prop.getProperties().entrySet()) {
                                    String key = propEntry.getKey();
                                    String value = propEntry.getValue();
                                    actionConf.set(key, value);
                                    LOG.debug("property : '" + key + "', value : '" + value + "'");
                                }
                            }
                        }
                    } else {
                        LOG.warn("No credential properties found for action : " + action.getId() + ", cred : " + action.getCred());
                    }
                } else {
                    LOG.info("Skipping credentials (" + OOZIE_CREDENTIALS_SKIP + "=true)");
                }
            } else {
                LOG.info("Skipping credentials (" + OOZIE_CREDENTIALS_SKIP + "=true)");
            }
        } else {
            LOG.warn("context or action is null");
        }
        return credPropertiesMap;
    }

    protected void setCredentialTokens(Credentials credentials, Configuration jobconf, Context context, WorkflowAction action,
            HashMap<String, CredentialsProperties> credPropertiesMap) throws Exception {

        if (context != null && action != null && credPropertiesMap != null) {
            // Make sure we're logged into Kerberos; if not, or near expiration, it will relogin
            CredentialsProviderFactory.ensureKerberosLogin();
            for (Entry<String, CredentialsProperties> entry : credPropertiesMap.entrySet()) {
                String credName = entry.getKey();
                CredentialsProperties credProps = entry.getValue();
                if (credProps != null) {
                    CredentialsProvider tokenProvider = CredentialsProviderFactory.getInstance()
                            .createCredentialsProvider(credProps.getType());
                    if (tokenProvider != null) {
                        tokenProvider.updateCredentials(credentials, jobconf, credProps, context);
                        LOG.debug("Retrieved Credential '" + credName + "' for action " + action.getId());
                    }
                    else {
                        LOG.debug("Credentials object is null for name= " + credName + ", type=" + credProps.getType());
                        throw new ActionExecutorException(ActionExecutorException.ErrorType.ERROR, "JA020",
                            "Could not load credentials of type [{0}] with name [{1}]]; perhaps it was not defined"
                                + " in oozie-site.xml?", credProps.getType(), credName);
                    }
                }
            }
        }
    }

    protected HashMap<String, CredentialsProperties> getActionCredentialsProperties(Context context,
            WorkflowAction action) throws Exception {
        HashMap<String, CredentialsProperties> props = new HashMap<String, CredentialsProperties>();
        if (context != null && action != null) {
            String credsInAction = action.getCred();
            if (credsInAction != null) {
                LOG.debug("Get credential '" + credsInAction + "' properties for action : " + action.getId());
                String[] credNames = credsInAction.split(",");
                for (String credName : credNames) {
                    CredentialsProperties credProps = getCredProperties(context, credName);
                    props.put(credName, credProps);
                }
            }
        }
        else {
            LOG.warn("context or action is null");
        }
        return props;
    }

    @SuppressWarnings("unchecked")
    protected CredentialsProperties getCredProperties(Context context, String credName)
            throws Exception {
        CredentialsProperties credProp = null;
        String workflowXml = ((WorkflowJobBean) context.getWorkflow()).getWorkflowInstance().getApp().getDefinition();
        XConfiguration wfjobConf = getWorkflowConf(context);
        Element elementJob = XmlUtils.parseXml(workflowXml);
        Element credentials = elementJob.getChild("credentials", elementJob.getNamespace());
        if (credentials != null) {
            for (Element credential : (List<Element>) credentials.getChildren("credential", credentials.getNamespace())) {
                String name = credential.getAttributeValue("name");
                String type = credential.getAttributeValue("type");
                LOG.debug("getCredProperties: Name: " + name + ", Type: " + type);
                if (name.equalsIgnoreCase(credName)) {
                    credProp = new CredentialsProperties(name, type);
                    for (Element property : (List<Element>) credential.getChildren("property",
                            credential.getNamespace())) {
                        String propertyName = property.getChildText("name", property.getNamespace());
                        String propertyValue = property.getChildText("value", property.getNamespace());
                        ELEvaluator eval = new ELEvaluator();
                        for (Map.Entry<String, String> entry : wfjobConf) {
                            eval.setVariable(entry.getKey(), entry.getValue().trim());
                        }
                        propertyName = eval.evaluate(propertyName, String.class);
                        propertyValue = eval.evaluate(propertyValue, String.class);

                        credProp.getProperties().put(propertyName, propertyValue);
                        LOG.debug("getCredProperties: Properties name :'" + propertyName + "', Value : '"
                                + propertyValue + "'");
                    }
                }
            }
            if (credProp == null && credName != null) {
                throw new ActionExecutorException(ActionExecutorException.ErrorType.ERROR, "JA021",
                        "Could not load credentials with name [{0}]].", credName);
            }
        } else {
            LOG.debug("credentials is null for the action");
        }
        return credProp;
    }

    @Override
    public void start(Context context, WorkflowAction action) throws ActionExecutorException {
        LogUtils.setLogInfo(action);
        try {
            LOG.debug("Starting action " + action.getId() + " getting Action File System");
            FileSystem actionFs = context.getAppFileSystem();
            LOG.debug("Preparing action Dir through copying " + context.getActionDir());
            prepareActionDir(actionFs, context);
            LOG.debug("Action Dir is ready. Submitting the action ");
            submitLauncher(actionFs, context, action);
            LOG.debug("Action submit completed. Performing check ");
            check(context, action);
            LOG.debug("Action check is done after submission");
        }
        catch (Exception ex) {
            throw convertException(ex);
        }
    }

    @Override
    public void end(Context context, WorkflowAction action) throws ActionExecutorException {
        try {
            String externalStatus = action.getExternalStatus();
            WorkflowAction.Status status = externalStatus.equals(SUCCEEDED) ? WorkflowAction.Status.OK
                    : WorkflowAction.Status.ERROR;
            context.setEndData(status, getActionSignal(status));
        }
        catch (Exception ex) {
            throw convertException(ex);
        }
        finally {
            try {
                FileSystem actionFs = context.getAppFileSystem();
                cleanUpActionDir(actionFs, context);
            }
            catch (Exception ex) {
                throw convertException(ex);
            }
        }
    }

    /**
     * Create job client object
     *
     * @param context
     * @param jobConf
     * @return JobClient
     * @throws HadoopAccessorException
     */
    protected JobClient createJobClient(Context context, Configuration jobConf) throws HadoopAccessorException {
        String user = context.getWorkflow().getUser();
        return Services.get().get(HadoopAccessorService.class).createJobClient(user, jobConf);
    }

    /**
     * Create yarn client object
     *
     * @param context
     * @param jobConf
     * @return YarnClient
     * @throws HadoopAccessorException
     */
    protected YarnClient createYarnClient(Context context, Configuration jobConf) throws HadoopAccessorException {
        String user = context.getWorkflow().getUser();
        return Services.get().get(HadoopAccessorService.class).createYarnClient(user, jobConf);
    }

    /**
     * Useful for overriding in actions that do subsequent job runs
     * such as the MapReduce Action, where the launcher job is not the
     * actual job that then gets monitored.
     */
    protected String getActualExternalId(WorkflowAction action) {
        return action.getExternalId();
    }

    /**
     * If returns true, it means that we have to add Hadoop MR jars to the classpath.
     * Subclasses should override this method if necessary. By default we don't add
     * MR jars to the classpath.
     * @return false by default
     */
    protected boolean needToAddMapReduceToClassPath() {
        return false;
    }

    /**
     * Adds action-specific environment variables. Default implementation is no-op.
     * Subclasses should override this method if necessary.
     *
     */
    protected void addActionSpecificEnvVars(Map<String, String> env) {
        // nop
    }

    @Override
    public void check(Context context, WorkflowAction action) throws ActionExecutorException {
        boolean fallback = false;
        LOG = XLog.resetPrefix(LOG);
        LogUtils.setLogInfo(action);
        YarnClient yarnClient = null;
        try {
            Element actionXml = XmlUtils.parseXml(action.getConf());
            Configuration jobConf = createBaseHadoopConf(context, actionXml);
            FileSystem actionFs = context.getAppFileSystem();
            yarnClient = createYarnClient(context, jobConf);
            FinalApplicationStatus appStatus = null;
            try {
                ApplicationReport appReport =
                        yarnClient.getApplicationReport(ConverterUtils.toApplicationId(action.getExternalId()));
                YarnApplicationState appState = appReport.getYarnApplicationState();
                if (appState == YarnApplicationState.FAILED || appState == YarnApplicationState.FINISHED
                        || appState == YarnApplicationState.KILLED) {
                    appStatus = appReport.getFinalApplicationStatus();
                }

            } catch (Exception ye) {
                LOG.warn("Exception occurred while checking Launcher AM status; will try checking action data file instead ", ye);
                // Fallback to action data file if we can't find the Launcher AM (maybe it got purged)
                fallback = true;
            }
            if (appStatus != null || fallback) {
                Path actionDir = context.getActionDir();
                // load sequence file into object
                Map<String, String> actionData = LauncherHelper.getActionData(actionFs, actionDir, jobConf);
                if (fallback) {
                    String finalStatus = actionData.get(LauncherAM.ACTION_DATA_FINAL_STATUS);
                    if (finalStatus != null) {
                        appStatus = FinalApplicationStatus.valueOf(finalStatus);
                    } else {
                        context.setExecutionData(FAILED, null);
                        throw new ActionExecutorException(ActionExecutorException.ErrorType.FAILED, "JA017",
                                "Unknown hadoop job [{0}] associated with action [{1}] and couldn't determine status from" +
                                        " action data.  Failing this action!", action.getExternalId(), action.getId());
                    }
                }

                String externalID = actionData.get(LauncherAM.ACTION_DATA_NEW_ID);  // MapReduce was launched
                if (externalID != null) {
                    context.setExternalChildIDs(externalID);
                    LOG.info(XLog.STD, "Hadoop Job was launched : [{0}]", externalID);
                }

               // Multiple child IDs - Pig or Hive action
                String externalIDs = actionData.get(LauncherAM.ACTION_DATA_EXTERNAL_CHILD_IDS);
                if (externalIDs != null) {
                    context.setExternalChildIDs(externalIDs);
                    LOG.info(XLog.STD, "External Child IDs  : [{0}]", externalIDs);

                }

                LOG.info(XLog.STD, "action completed, external ID [{0}]", action.getExternalId());
                context.setExecutionData(appStatus.toString(), null);
                if (appStatus == FinalApplicationStatus.SUCCEEDED) {
                    if (getCaptureOutput(action) && LauncherHelper.hasOutputData(actionData)) {
                        context.setExecutionData(SUCCEEDED, PropertiesUtils.stringToProperties(actionData
                                .get(LauncherAM.ACTION_DATA_OUTPUT_PROPS)));
                        LOG.info(XLog.STD, "action produced output");
                    }
                    else {
                        context.setExecutionData(SUCCEEDED, null);
                    }
                    if (LauncherHelper.hasStatsData(actionData)) {
                        context.setExecutionStats(actionData.get(LauncherAM.ACTION_DATA_STATS));
                        LOG.info(XLog.STD, "action produced stats");
                    }
                    getActionData(actionFs, action, context);
                }
                else {
                    String errorReason;
                    if (actionData.containsKey(LauncherAM.ACTION_DATA_ERROR_PROPS)) {
                        Properties props = PropertiesUtils.stringToProperties(actionData
                                .get(LauncherAM.ACTION_DATA_ERROR_PROPS));
                        String errorCode = props.getProperty("error.code");
                        if ("0".equals(errorCode)) {
                            errorCode = "JA018";
                        }
                        if ("-1".equals(errorCode)) {
                            errorCode = "JA019";
                        }
                        errorReason = props.getProperty("error.reason");
                        LOG.warn("Launcher ERROR, reason: {0}", errorReason);
                        String exMsg = props.getProperty("exception.message");
                        String errorInfo = (exMsg != null) ? exMsg : errorReason;
                        context.setErrorInfo(errorCode, errorInfo);
                        String exStackTrace = props.getProperty("exception.stacktrace");
                        if (exMsg != null) {
                            LOG.warn("Launcher exception: {0}{E}{1}", exMsg, exStackTrace);
                        }
                    }
                    else {
                        errorReason = XLog.format("Launcher AM died, check Hadoop LOG for job [{0}:{1}]", action
                                .getTrackerUri(), action.getExternalId());
                        LOG.warn(errorReason);
                    }
                    context.setExecutionData(FAILED_KILLED, null);
                }
            }
            else {
                context.setExternalStatus(YarnApplicationState.RUNNING.toString());
                LOG.info(XLog.STD, "checking action, hadoop job ID [{0}] status [RUNNING]",
                        action.getExternalId());
            }
        }
        catch (Exception ex) {
            LOG.warn("Exception in check(). Message[{0}]", ex.getMessage(), ex);
            throw convertException(ex);
        }
        finally {
            if (yarnClient != null) {
                IOUtils.closeQuietly(yarnClient);
            }
        }
    }

    /**
     * Get the output data of an action. Subclasses should override this method
     * to get action specific output data.
     * @param actionFs the FileSystem object
     * @param action the Workflow action
     * @param context executor context
     * @throws org.apache.oozie.service.HadoopAccessorException
     * @throws org.jdom.JDOMException
     * @throws java.io.IOException
     * @throws java.net.URISyntaxException
     *
     */
    protected void getActionData(FileSystem actionFs, WorkflowAction action, Context context)
            throws HadoopAccessorException, JDOMException, IOException, URISyntaxException {
    }

    protected boolean getCaptureOutput(WorkflowAction action) throws JDOMException {
        Element eConf = XmlUtils.parseXml(action.getConf());
        Namespace ns = eConf.getNamespace();
        Element captureOutput = eConf.getChild("capture-output", ns);
        return captureOutput != null;
    }

    @Override
    public void kill(Context context, WorkflowAction action) throws ActionExecutorException {
        YarnClient yarnClient = null;
        try {
            Element actionXml = XmlUtils.parseXml(action.getConf());
            final Configuration jobConf = createBaseHadoopConf(context, actionXml);
            String launcherTag = LauncherHelper.getActionYarnTag(jobConf, context.getWorkflow().getParentId(), action);
            jobConf.set(LauncherMain.CHILD_MAPREDUCE_JOB_TAGS, LauncherHelper.getTag(launcherTag));
            yarnClient = createYarnClient(context, jobConf);
            if(action.getExternalId() != null) {
                try {
                    LOG.info("Killing action {0}'s external application {1}", action.getId(), action.getExternalId());
                    yarnClient.killApplication(ConverterUtils.toApplicationId(action.getExternalId()));
                } catch (Exception e) {
                    LOG.warn("Could not kill {0}", action.getExternalId(), e);
                }
            }
            String externalChildIDs = action.getExternalChildIDs();
            if(externalChildIDs != null) {
                for(String childId : externalChildIDs.split(",")) {
                    try {
                        LOG.info("Killing action {0}'s external child application {1}", action.getId(), childId);
                        yarnClient.killApplication(ConverterUtils.toApplicationId(childId.trim()));
                    } catch (Exception e) {
                        LOG.warn("Could not kill external child of {0}, {1}", action.getExternalId(),
                                childId, e);
                    }
                }
            }
            for(ApplicationId id : LauncherMain.getChildYarnJobs(jobConf, ApplicationsRequestScope.ALL,
                    action.getStartTime().getTime())){
                try {
                    LOG.info("Killing action {0}'s external child application {1} based on tags",
                            action.getId(), id.toString());
                    yarnClient.killApplication(id);
                } catch (Exception e) {
                    LOG.warn("Could not kill child of {0}, {1}", action.getExternalId(), id, e);
                }
            }

            context.setExternalStatus(KILLED);
            context.setExecutionData(KILLED, null);
        } catch (Exception ex) {
            LOG.error("Error when killing YARN application", ex);
            throw convertException(ex);
        } finally {
            try {
                FileSystem actionFs = context.getAppFileSystem();
                cleanUpActionDir(actionFs, context);
                Closeables.closeQuietly(yarnClient);
            } catch (Exception ex) {
                LOG.error("Error when cleaning up action dir", ex);
                throw convertException(ex);
            }
        }
    }

    private static Set<String> FINAL_STATUS = new HashSet<String>();

    static {
        FINAL_STATUS.add(SUCCEEDED);
        FINAL_STATUS.add(KILLED);
        FINAL_STATUS.add(FAILED);
        FINAL_STATUS.add(FAILED_KILLED);
    }

    @Override
    public boolean isCompleted(String externalStatus) {
        return FINAL_STATUS.contains(externalStatus);
    }


    /**
     * Return the sharelib names for the action.
     * <p>
     * If <code>NULL</code> or empty, it means that the action does not use the action
     * sharelib.
     * <p>
     * If a non-empty string, i.e. <code>foo</code>, it means the action uses the
     * action sharelib sub-directory <code>foo</code> and all JARs in the sharelib
     * <code>foo</code> directory will be in the action classpath. Multiple sharelib
     * sub-directories can be specified as a comma separated list.
     * <p>
     * The resolution is done using the following precedence order:
     * <ul>
     *     <li><b>action.sharelib.for.#ACTIONTYPE#</b> in the action configuration</li>
     *     <li><b>action.sharelib.for.#ACTIONTYPE#</b> in the job configuration</li>
     *     <li><b>action.sharelib.for.#ACTIONTYPE#</b> in the oozie configuration</li>
     *     <li>Action Executor <code>getDefaultShareLibName()</code> method</li>
     * </ul>
     *
     *
     * @param context executor context.
     * @param actionXml
     * @param conf action configuration.
     * @return the action sharelib names.
     */
    protected String[] getShareLibNames(Context context, Element actionXml, Configuration conf) {
        String[] names = conf.getStrings(ACTION_SHARELIB_FOR + getType());
        if (names == null || names.length == 0) {
            try {
                XConfiguration jobConf = getWorkflowConf(context);
                names = jobConf.getStrings(ACTION_SHARELIB_FOR + getType());
                if (names == null || names.length == 0) {
                    names = Services.get().getConf().getStrings(ACTION_SHARELIB_FOR + getType());
                    if (names == null || names.length == 0) {
                        String name = getDefaultShareLibName(actionXml);
                        if (name != null) {
                            names = new String[] { name };
                        }
                    }
                }
            }
            catch (IOException ex) {
                throw new RuntimeException("It cannot happen, " + ex.toString(), ex);
            }
        }
        return names;
    }

    private final static String ACTION_SHARELIB_FOR = "oozie.action.sharelib.for.";


    /**
     * Returns the default sharelib name for the action if any.
     *
     * @param actionXml the action XML fragment.
     * @return the sharelib name for the action, <code>NULL</code> if none.
     */
    protected String getDefaultShareLibName(Element actionXml) {
        return null;
    }

    public String[] getShareLibFilesForActionConf() {
        return null;
    }

    /**
     * Sets some data for the action on completion
     *
     * @param context executor context
     * @param actionFs the FileSystem object
     * @throws java.io.IOException
     * @throws org.apache.oozie.service.HadoopAccessorException
     * @throws java.net.URISyntaxException
     */
    protected void setActionCompletionData(Context context, FileSystem actionFs) throws IOException,
            HadoopAccessorException, URISyntaxException {
    }

    private void injectJobInfo(Configuration launcherJobConf, Configuration actionConf, Context context, WorkflowAction action) {
        if (OozieJobInfo.isJobInfoEnabled()) {
            try {
                OozieJobInfo jobInfo = new OozieJobInfo(actionConf, context, action);
                String jobInfoStr = jobInfo.getJobInfo();
                launcherJobConf.set(OozieJobInfo.JOB_INFO_KEY, jobInfoStr + "launcher=true");
                actionConf.set(OozieJobInfo.JOB_INFO_KEY, jobInfoStr + "launcher=false");
            }
            catch (Exception e) {
                // Just job info, should not impact the execution.
                LOG.error("Error while populating job info", e);
            }
        }
    }

    @Override
    public boolean requiresNameNodeJobTracker() {
        return true;
    }

    @Override
    public boolean supportsConfigurationJobXML() {
        return true;
    }

    private XConfiguration getWorkflowConf(Context context) throws IOException {
        if (workflowConf == null) {
            workflowConf = new XConfiguration(new StringReader(context.getWorkflow().getConf()));
        }
        return workflowConf;

    }

    private String getActionTypeLauncherPrefix() {
        return "oozie.action." + getType() + ".launcher.";
    }
}
