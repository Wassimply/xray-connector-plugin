/**
 * XP.RAVEN Project
 * <p>
 * Copyright (C) 2016 Xpand IT.
 * <p>
 * This software is proprietary.
 */
package com.xpandit.plugins.xrayjenkins.task;

import com.xpandit.plugins.xrayjenkins.Utils.ConfigurationUtils;
import com.xpandit.plugins.xrayjenkins.Utils.FormUtils;
import com.xpandit.plugins.xrayjenkins.Utils.BuilderUtils;
import com.xpandit.plugins.xrayjenkins.Utils.ProxyUtil;
import com.xpandit.plugins.xrayjenkins.exceptions.XrayJenkinsGenericException;
import com.xpandit.plugins.xrayjenkins.model.HostingType;
import com.xpandit.plugins.xrayjenkins.services.enviromentvariables.XrayEnvironmentVariableSetter;
import com.xpandit.plugins.xrayjenkins.task.compatibility.XrayExportBuilderCompatibilityDelegate;
import com.xpandit.xray.service.impl.XrayExporterCloudImpl;
import com.xpandit.xray.service.impl.delegates.HttpRequestProvider;
import hudson.EnvVars;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import com.xpandit.plugins.xrayjenkins.model.ServerConfiguration;
import com.xpandit.plugins.xrayjenkins.model.XrayInstance;
import com.xpandit.xray.exception.XrayClientCoreGenericException;
import com.xpandit.xray.service.XrayExporter;
import com.xpandit.xray.service.impl.XrayExporterImpl;
import hudson.AbortException;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.tasks.SimpleBuildStep;
import net.sf.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.xpandit.plugins.xrayjenkins.Utils.ConfigurationUtils.getConfiguration;
import static com.xpandit.plugins.xrayjenkins.Utils.EnvironmentVariableUtil.expandVariable;

/**
 * This class is responsible for performing the Xray: Cucumber Features Export Task
 * development guidelines for compatibility:
 * The class internal structure was modified in version 1.3.0 so the builder could be compatible with pipeline projects.
 * When developing in this class, compatibility for pré-1.3.0 versions must be ensured.
 * The following cases must always be considered:
 * 1 - 	the job is being created in version 1.3.0 or higher and the deprecated fields must be
 * 		populated for the case the user needs to perform a downgrade.
 *
 * 2 - 	the job was created on a pré-1.3.0 version, but has never been runned in 1.3.0 or higher versions.
 * 		In this case, if the user opens the job configurations, the fields must be populated.
 *
 * 3 - 	the job was created on pré-1.3.0. blueprint String fields need to be populated with values on perform.
 *
 * Any possible scenario must also be considered.
 * @see com.xpandit.plugins.xrayjenkins.task.compatibility.XrayExportBuilderCompatibilityDelegate
 */
public class XrayExportBuilder extends Builder implements SimpleBuildStep {

    private static final Logger LOG = LoggerFactory.getLogger(XrayExportBuilder.class);

    /**
     * this is only kept for backward compatibility (previous from 1.3.0)
     * In the future, when there is no risk that any client is still using legacy versions, we should consider removing it.
     * @deprecated since version 1.3.0, use blue print String fields instead.
     */
    @Deprecated
    private XrayInstance xrayInstance;

    /**
     * this is only kept for backward compatibility (previous from 1.3.0)
     * In the future, when there is no risk that any client is still using legacy versions, we should consider removing it.
     * @deprecated since version 1.3.0, use blue print String fields instead.
     */
    @Deprecated
    private Map<String,String> fields;

    private String serverInstance;//Configuration ID of the Jira instance
    private String issues;
    private String filter;
    private String filePath;

    /**
     * Constructor used in pipelines projects
     *
     * "Anyway code run from Pipeline should take any configuration values as literal strings
     * and make no attempt to perform variable substitution"
     * @see <a href="https://jenkins.io/doc/developer/plugin-development/pipeline-integration/">Writing Pipeline-Compatible Plugins </a>
     * @param serverInstance the server configuration id
     * @param issues the issues to export
     * @param filter the saved filter id
     * @param filePath the file path to export
     */
    @DataBoundConstructor
	public XrayExportBuilder(String serverInstance,
                             String issues,
                             String filter,
                             String filePath){
        this.issues = issues;
        this.filter = filter;
        this.filePath = filePath;
        this.serverInstance = serverInstance;

        //compatibility assigns
        this.xrayInstance = ConfigurationUtils.getConfiguration(serverInstance);
        this.fields = getFieldsMap(issues, filter, filePath);
    }

    private Map<String, String> getFieldsMap(String issues,
                                             String filter,
                                             String filePath){
        Map<String, String> fields = new HashMap<>();
        if(StringUtils.isNotBlank(issues)){
            fields.put("issues", issues);
        }
        if(StringUtils.isNotBlank(filter)){
            fields.put("filter", filter);
        }
        if(StringUtils.isNotBlank(filePath)){
            fields.put("filePath", filePath);
        }
        return fields;
    }

    @Override
    public void perform(Run<?,?> build,
                        FilePath workspace,
                        Launcher launcher,
                        TaskListener listener) throws IOException {

        XrayExportBuilderCompatibilityDelegate compatibilityDelegate = new XrayExportBuilderCompatibilityDelegate(this);
        compatibilityDelegate.applyCompatibility();
        
        listener.getLogger().println("Starting XRAY: Cucumber Features Export Task...");
        
        listener.getLogger().println("##########################################################");
        listener.getLogger().println("####   Xray is exporting the feature files  ####");
        listener.getLogger().println("##########################################################");
        XrayInstance serverInstance = getConfiguration(this.serverInstance);

        if(serverInstance == null){
            listener.getLogger().println("XrayInstance is null. please check the passed configuration ID");

            XrayEnvironmentVariableSetter
                    .failed("XrayInstance is null. please check the passed configuration ID")
                    .setAction(build, listener);
            throw new AbortException("The Jira server configuration of this task was not found.");
        }

        final HttpRequestProvider.ProxyBean proxyBean = ProxyUtil.createProxyBean();
        XrayExporter client;

        if (serverInstance.getHosting() == HostingType.CLOUD) {
            client = new XrayExporterCloudImpl(serverInstance.getCredential(build).getUsername(),
                    serverInstance.getCredential(build).getPassword(),
                    proxyBean);
        } else if (serverInstance.getHosting() == null || serverInstance.getHosting() == HostingType.SERVER) {
            client = new XrayExporterImpl(serverInstance.getServerAddress(),
                    serverInstance.getCredential(build).getUsername(),
                    serverInstance.getCredential(build).getPassword(),
                    proxyBean);
        } else {
            XrayEnvironmentVariableSetter
                    .failed("Hosting type not recognized.")
                    .setAction(build, listener);
            throw new XrayJenkinsGenericException("Hosting type not recognized.");
        }
        
        try {
            final EnvVars env = build.getEnvironment(listener);
            final String expandedIssues = expandVariable(env, issues);
            final String expandedFilter = expandVariable(env, filter);
            final String expandedFilePath = expandVariable(env, filePath);

            if (StringUtils.isNotBlank(expandedIssues)) {
                listener.getLogger().println("Issues: " + expandedIssues);
            }
            if (StringUtils.isNotBlank(expandedFilter)) {
                listener.getLogger().println("Filter: " + expandedFilter);
            }
            if (StringUtils.isNotBlank(expandedFilePath)) {
                listener.getLogger().println("Will save the feature files in: " + expandedFilePath);
            }
            
            InputStream file = client.downloadFeatures(expandedIssues, expandedFilter,"true");
            this.unzipFeatures(listener, workspace, expandedFilePath, file);
            
            listener.getLogger().println("Successfully exported the Cucumber features");

            // Sets the Xray Build Environment Variables
            XrayEnvironmentVariableSetter
                    .success()
                    .setAction(build, listener);
        } catch (XrayClientCoreGenericException | IOException | InterruptedException e) {
            e.printStackTrace();
            listener.error(e.getMessage());

            XrayEnvironmentVariableSetter
                    .failed()
                    .setAction(build, listener);

            throw new AbortException(e.getMessage());
        } finally {
            client.shutdown();
        }
    }
    
    private void unzipFeatures(TaskListener listener, FilePath workspace, String filePath, InputStream zip) throws IOException, InterruptedException {

        if (StringUtils.isBlank(filePath)) {
            filePath = "features/";
        }

        FilePath outputFile = new FilePath(workspace, filePath.trim());
        listener.getLogger().println("###################### Unzipping file ####################");
        outputFile.mkdirs();
        outputFile.unzipFrom(zip);
        listener.getLogger().println("###################### Unzipped file #####################");
    }

    
    public String getServerInstance() {
		return serverInstance;
	}

	public void setServerInstance(String serverInstance) {
		this.serverInstance = serverInstance;
	}


	public String getIssues() {
		return issues;
	}

	public void setIssues(String issues) {
		this.issues = issues;
	}


	public String getFilter() {
		return filter;
	}

	public void setFilter(String filter) {
		this.filter = filter;
	}


	public String getFilePath() {
		return filePath;
	}

	public void setFilePath(String filePath) {
		this.filePath = filePath;
	}

    public XrayInstance getXrayInstance() {
        return xrayInstance;
    }

    @DataBoundSetter
    public void setXrayInstance(XrayInstance xrayInstance) {
        this.xrayInstance = xrayInstance;
    }

    public Map<String, String> getFields() {
        return fields;
    }

    @DataBoundSetter
    public void setFields(Map<String, String> fields) {
        this.fields = fields;
    }

    @Extension
    public static class Descriptor extends BuildStepDescriptor<Builder> {

        public Descriptor() {
        	super(XrayExportBuilder.class);
            load();
        }
        
        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
           
        	save();
            return true;
            
        }
        
        @Override
		public XrayExportBuilder newInstance(StaplerRequest req, JSONObject formData) throws Descriptor.FormException{
			validateFormData(formData);
        	Map<String,String> fields = getFields(formData.getJSONObject("fields"));
            return new XrayExportBuilder(formData.getString("serverInstance"),
                    fields.get("issues"),
                    fields.get("filter"),
                    fields.get("filePath"));
			
        }

        private void validateFormData(JSONObject formData) throws Descriptor.FormException{
            if(StringUtils.isBlank(formData.getString("serverInstance"))){
                throw new Descriptor.FormException("Xray Cucumber Features Export Task error, you must provide a valid Jira Instance","serverInstance");
            }
        }

        
        public ListBoxModel doFillServerInstanceItems() {
        	return FormUtils.getServerInstanceItems();
        }

        private Map<String, String> getFields(JSONObject configuredFields) {
        	Map<String,String> fields = new HashMap<String,String>();
        	
        	Set<String> keys = configuredFields.keySet();
        	
        	for(String key : keys){
        		if(configuredFields.containsKey(key)){
        			String value = configuredFields.getString(key);
					if(StringUtils.isNotBlank(value))
						fields.put(key, value);
        		}
        	}
        	
        	return fields;
		}
		
		@Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            LOG.info("applying XrayExportBuilder to following jobType class: {}", jobType.getSimpleName());
            return BuilderUtils.isSupportedJobType(jobType);
        }

        @Override
        public String getDisplayName() {
            return "Xray: Cucumber Features Export Task";
        }

        /*
         * Checking if the file path doesn't contain "../"
         */
        public FormValidation doCheckFilePath(@QueryParameter String value) {

            if(value.contains("../")){
                return FormValidation.error("You can't provide file paths for upper directories.Please don't use \"../\".");
            }
            else{
                return FormValidation.ok();
            }
        }

        /*
         * Checking if either issues or filter is filled
         */
        public FormValidation doCheckIssues(@QueryParameter String value, @QueryParameter String filter) {
            if (StringUtils.isEmpty(value) && StringUtils.isEmpty(filter)) {
                return FormValidation.error("You must provide issue keys and/or a filter ID in order to export cucumber features from Xray.");
            }
            else{
                return FormValidation.ok();
            }
        }

        public FormValidation doCheckFilter(@QueryParameter String value, @QueryParameter String issues) {            
            if (StringUtils.isEmpty(value) && StringUtils.isEmpty(issues)) {
                return FormValidation.error("You must provide issue keys and/or a filter ID in order to export cucumber features from Xray.");
            }
            else{
                return FormValidation.ok();
            }
        }

        public FormValidation doCheckServerInstance(){
            return ConfigurationUtils.anyAvailableConfiguration() ? FormValidation.ok() : FormValidation.error("No configured Server Instances found");
        }

        
        public List<XrayInstance> getServerInstances() {
			return ServerConfiguration.get().getServerInstances();
		}
        
    }

}
