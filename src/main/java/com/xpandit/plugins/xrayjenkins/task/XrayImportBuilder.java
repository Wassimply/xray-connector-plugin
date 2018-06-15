/**
 * XP.RAVEN Project
 * <p>
 * Copyright (C) 2016 Xpand IT.
 * <p>
 * This software is proprietary.
 */
package com.xpandit.plugins.xrayjenkins.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xpandit.xray.model.QueryParameter;
import com.xpandit.xray.model.UploadResult;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.xpandit.plugins.xrayjenkins.exceptions.XrayJenkinsGenericException;
import javax.annotation.Nullable;
import org.apache.commons.lang3.StringUtils;
import org.kohsuke.stapler.StaplerRequest;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.xpandit.plugins.xrayjenkins.model.ServerConfiguration;
import com.xpandit.plugins.xrayjenkins.model.XrayInstance;
import com.xpandit.xray.exception.XrayClientCoreGenericException;
import com.xpandit.xray.model.Content;
import com.xpandit.xray.model.Endpoint;
import com.xpandit.xray.model.FormatBean;
import com.xpandit.xray.service.XrayImporter;
import com.xpandit.xray.service.impl.XrayImporterImpl;

import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.FreeStyleProject;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.tasks.SimpleBuildStep;
import net.sf.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Class description.
 *
 * @author <a href="mailto:sebastiao.maya@xpand-it.com">sebastiao.maya</a>
 * @version $Revision: 666 $
 *
 */
public class XrayImportBuilder extends Notifier implements SimpleBuildStep{
    
    private XrayInstance xrayInstance;
    private Endpoint endpoint;
    private Map<String,String> dynamicFields;
    private boolean importToSameExecution;
    
    private String formatSuffix; //value of format select
    private String serverInstance;//Configuration ID of the JIRA instance
    private String inputInfoSwitcher;//value of the input type switcher

	private static final Logger LOG = LoggerFactory.getLogger(XrayImportBuilder.class);
    
    
    private static Gson gson = new GsonBuilder().create();
    
    public XrayImportBuilder(XrayInstance xrayInstance, Endpoint endpoint, Map<String, String> dynamicFields) {
    	this.xrayInstance = xrayInstance;
    	this.endpoint = endpoint;
    	this.dynamicFields = dynamicFields;
    	this.importToSameExecution = Objects.equals(dynamicFields.get("sameExecutionCheckbox"), "true") ? true : false;
    	
    	this.formatSuffix = endpoint.getSuffix();
    	this.serverInstance = xrayInstance.getConfigID();
    	this.inputInfoSwitcher = dynamicFields.get("inputInfoSwitcher");
	}
	
    
    public Map<String,String> getDynamicFields(){
    	return dynamicFields;
    }
    
    public String getFormatSuffix(){
    	return formatSuffix;
    }
    
    public String getServerInstance(){
    	return serverInstance;
    }
    
    public void setServerInstance(String serverInstance){
    	this.serverInstance = serverInstance;
    }
   
    public void setFormatSuffix(String formatSuffix){
    	this.formatSuffix = formatSuffix;
    }
	
	public Endpoint getEndpoint(){
		return this.endpoint;
	}
	
	public String getFormatName(){
		return this.endpoint.getName();
	}
	
	public String getInputInfoSwitcher() {
		return inputInfoSwitcher;
	}

	public void setInputInfoSwitcher(String inputInfoSwitcher) {
		this.inputInfoSwitcher = inputInfoSwitcher;
	}

	public boolean isImportToSameExecution() {
		return importToSameExecution;
	}

	public String defaultFormats(){
        Map<String,FormatBean> formats = new HashMap<String,FormatBean>();
        for(Endpoint e : Endpoint.values()){
        	FormatBean bean = e.toBean();
        	formats.put(e.getSuffix(),bean);
        	
        	if(e.name().equals(endpoint.name()))
        		bean.setFieldsConfiguration(dynamicFields);
        }
        return gson.toJson(formats);	
    }
    
	private List<FilePath> getFilePaths(FilePath workspace, String filePath, TaskListener listener)
			throws IOException {
    	List<FilePath> filePaths = new ArrayList<>();
		if(workspace == null){
			throw new XrayJenkinsGenericException("No workspace in this current node");
		}
		if(StringUtils.isBlank(filePath)){
			throw new XrayJenkinsGenericException("No file path was specified");
		}
		FilePath file = readFile(workspace,filePath.trim(),listener);
		List<File> files = getFileList(file.getRemote());
		if(files.isEmpty()){
			throw new XrayJenkinsGenericException("File path is a directory or no matching file exists");
		}
		for(File f : files){
			filePaths.add(new FilePath(f));
		}
		return filePaths;
	}

	private FilePath getFile(FilePath workspace, String filePath, TaskListener listener) throws IOException, InterruptedException{
		if(workspace == null){
			throw new XrayJenkinsGenericException("No workspace in this current node");
		}

		if(StringUtils.isBlank(filePath)){
			throw new XrayJenkinsGenericException("No file path was specified");
		}

		FilePath file = readFile(workspace,filePath.trim(),listener);

		getFileList(file.getRemote());

		if(file.isDirectory() || !file.exists()){
			throw new XrayJenkinsGenericException("File path is a directory or the file doesn't exist");
		}
		return file;
	}

	private List<File> getFileList(String globExpression) {
		List<File> files = new ArrayList<>();
		String [] parts = globExpression.split("\\\\");
		String glob = parts[parts.length - 1];
		PathMatcher matcher;
		try{
			matcher = FileSystems.getDefault().getPathMatcher("glob:"
					+ glob);
		} catch (IllegalArgumentException e){
			LOG.error("pattern is invalid", e);
			throw e;
		} catch (UnsupportedOperationException e){
			LOG.error("the pattern syntax is not known", e);
			throw e;
		}
		for(File file : getFileList(resolveFolders(globExpression))){
			Path fileName = file.toPath().getFileName();
			if (matcher.matches(fileName)) {
				files.add(file);
			}
		}
		return files;
	}

	private List<File> getFileList(List<String> paths){
		List<File> files = new ArrayList<>();
    	for(String path : paths){
			String [] parts = path.split("\\\\");
			String parentPath = rebuildPath(parts);
			File folder = new File(parentPath);
			File[] listOfFiles = folder.listFiles();
			files.addAll(Arrays.asList(listOfFiles));
		}
		return files;
	}

	private List<String> resolveFolders(String path) {
		List<String> paths = new ArrayList<>();
    	String [] parts = path.split("\\*\\*");
    	if(parts.length == 1){
    		paths.add(path);
    		return paths;
		}
		File folder = new File(parts[0]);
    	if(!folder.exists()){
    		return new ArrayList<>();
		}
    	for(File f : folder.listFiles()){
    		if(f.isDirectory()){
    			String newPath = f.getPath() + mergeParts(Arrays.copyOfRange(parts, 1, parts.length));
    			paths.addAll(resolveFolders(newPath));
			}
		}
		return paths;
	}

	private String mergeParts(String [] parts){
    	if(parts.length == 1){
    		return parts[0];
		}
		StringBuilder sb = new StringBuilder();
    	sb.append(parts[0]);
		for(int i = 1 ; i < parts.length; i ++){
    		sb.append("**").append(parts[i]);
		}
		return sb.toString();
	}

	private String rebuildPath(String [] parts){
    	StringBuilder sb = new StringBuilder();
    	for(int i = 0; i < parts.length - 1; i ++){
    		sb.append(parts[i]).append("\\");
		}
		return sb.toString();
	}
	
	private FilePath readFile(FilePath workspace, String filePath, TaskListener listener) throws IOException{
		   FilePath f = new FilePath(workspace, filePath);
		   listener.getLogger().println("File: "+f.getRemote());
		   return f;
	}
	
	private String expand(EnvVars environment, String variable){
		if(StringUtils.isNotBlank(variable)){
			String expanded = environment.expand(variable);
			return expanded.equals(variable) ? variable : expanded;
		}
		return "";
	}

	@Override
	public void perform(Run<?,?> build, FilePath workspace, Launcher launcher, TaskListener listener)
			throws InterruptedException, IOException {
		validate(dynamicFields);

		listener.getLogger().println("Starting import task...");

		listener.getLogger().println("Import Cucumber features Task started...");

		listener.getLogger().println("##########################################################");
		listener.getLogger().println("####   Xray for JIRA is importing the feature files  ####");
		listener.getLogger().println("##########################################################");

		XrayImporter client = new XrayImporterImpl(xrayInstance.getServerAddress(),xrayInstance.getUsername(),xrayInstance.getPassword());
		EnvVars env = build.getEnvironment(listener);
		String importFilePath = dynamicFields.get(com.xpandit.xray.model.DataParameter.FILEPATH.getKey());
		String resolved = this.expand(env,importFilePath);

		if(Endpoint.JUNIT.equals(this.endpoint)
				|| Endpoint.NUNIT.equals(this.endpoint)
				|| Endpoint.TESTNG.equals(this.endpoint)
				|| Endpoint.ROBOT.equals(this.endpoint)){
			List<FilePath> resultsFile = getFilePaths(workspace,resolved,listener);
			UploadResult result;
			ObjectMapper mapper = new ObjectMapper();
			String key = null;
			for(FilePath fp : resultsFile){
				result = uploadResults(workspace, listener,client, fp, env, key);
				if(key == null && this.importToSameExecution){
					Map<String, Map> map = mapper.readValue(result.getMessage(), Map.class);
					key = (String) map.get("testExecIssue").get("key");
				}
			}
		} else{
			FilePath file = getFile(workspace, resolved, listener);
			uploadResults(workspace, listener, client, file, env, null);
		}

	}

	private UploadResult uploadResults(FilePath workspace,
									   TaskListener listener,
									   XrayImporter client,
									   FilePath resultsFile,
									   EnvVars env,
									   @Nullable String testExecutionKey)
			throws InterruptedException, IOException{
		try {
			Map<com.xpandit.xray.model.QueryParameter,String> queryParams = prepareQueryParam(env);

			if(StringUtils.isBlank(dynamicFields.get(com.xpandit.xray.model.QueryParameter.TEST_EXEC_KEY.getKey()))
					&& StringUtils.isNotBlank(testExecutionKey)
					&& this.importToSameExecution){
				queryParams.put(com.xpandit.xray.model.QueryParameter.TEST_EXEC_KEY, testExecutionKey);
			}

			String importFilePath = dynamicFields.get(com.xpandit.xray.model.DataParameter.FILEPATH.getKey());
			String importInfo = dynamicFields.get(com.xpandit.xray.model.DataParameter.INFO.getKey());

			Map<com.xpandit.xray.model.DataParameter,Content> dataParams = new HashMap<>();

			if(StringUtils.isNotBlank(importFilePath)){
				Content results = new com.xpandit.xray.model.FileStream(resultsFile.getName(),resultsFile.read(),
						endpoint.getResultsMediaType());

				dataParams.put(com.xpandit.xray.model.DataParameter.FILEPATH, results);
			}
			if(StringUtils.isNotBlank(importInfo)){
				String resolved = this.expand(env,importInfo);
				String inputInfoSwitcher = dynamicFields.get("inputInfoSwitcher");

				Content info;
				if(inputInfoSwitcher.equals("filePath")){
					FilePath infoFile = getFile(workspace,resolved,listener);
					info = new com.xpandit.xray.model.FileStream(infoFile.getName(),infoFile.read(),endpoint.getInfoFieldMediaType());
				}else{
					info = new com.xpandit.xray.model.StringContent(resolved, endpoint.getInfoFieldMediaType());
				}

				dataParams.put(com.xpandit.xray.model.DataParameter.INFO, info);
			}

			UploadResult resut = client.uploadResults(endpoint, dataParams, queryParams);

			listener.getLogger().println("Sucessfully imported "+endpoint.getName()+" results");
			return resut;

		}catch(XrayClientCoreGenericException e){
			e.printStackTrace();
			throw new AbortException(e.getMessage());
		}catch(XrayJenkinsGenericException e){
			e.printStackTrace();
			throw new AbortException(e.getMessage());
		}catch (IOException e) {
			e.printStackTrace();
			listener.error(e.getMessage());
			throw new IOException(e);
		}finally{
			client.shutdown();
		}
	}

	private Map<com.xpandit.xray.model.QueryParameter, String> prepareQueryParam(EnvVars env){
		Map<com.xpandit.xray.model.QueryParameter,String> queryParams = new EnumMap<>(QueryParameter.class);
		String projectKey = dynamicFields.get(com.xpandit.xray.model.QueryParameter.PROJECT_KEY.getKey());
		queryParams.put(com.xpandit.xray.model.QueryParameter.PROJECT_KEY, this.expand(env,projectKey));

		String testExecKey = dynamicFields.get(com.xpandit.xray.model.QueryParameter.TEST_EXEC_KEY.getKey());
		queryParams.put(com.xpandit.xray.model.QueryParameter.TEST_EXEC_KEY, this.expand(env,testExecKey));

		String testPlanKey = dynamicFields.get(com.xpandit.xray.model.QueryParameter.TEST_PLAN_KEY.getKey());
		queryParams.put(com.xpandit.xray.model.QueryParameter.TEST_PLAN_KEY, this.expand(env,testPlanKey));

		String testEnvironments = dynamicFields.get(com.xpandit.xray.model.QueryParameter.TEST_ENVIRONMENTS.getKey());
		queryParams.put(com.xpandit.xray.model.QueryParameter.TEST_ENVIRONMENTS, this.expand(env,testEnvironments));

		String revision = dynamicFields.get(com.xpandit.xray.model.QueryParameter.REVISION.getKey());
		queryParams.put(com.xpandit.xray.model.QueryParameter.REVISION, this.expand(env,revision));

		String fixVersion = dynamicFields.get(com.xpandit.xray.model.QueryParameter.FIX_VERSION.getKey());
		queryParams.put(com.xpandit.xray.model.QueryParameter.FIX_VERSION, this.expand(env,fixVersion));

		return queryParams;
	}

    private void validate(Map<String,String> dynamicFields) throws FormValidation{
      	 
      	 for(com.xpandit.xray.model.DataParameter dp : com.xpandit.xray.model.DataParameter.values()){
      		 if(dynamicFields.containsKey(dp.getKey()) && dp.isRequired()){
      			 String value = dynamicFields.get(dp.getKey());
      			 if(StringUtils.isBlank(value))
      				throw FormValidation.error("You must configure the field "+dp.getLabel());
      		 }
      	 }
      	 
      	for(com.xpandit.xray.model.QueryParameter qp : com.xpandit.xray.model.QueryParameter.values()){
      		 if(dynamicFields.containsKey(qp.getKey()) && qp.isRequired()){
      			 String value = dynamicFields.get(qp.getKey());
      			 if(StringUtils.isBlank(value))
      				throw FormValidation.error("You must configure the field "+qp.getLabel());
      		 }
      	 }
      	 
      	 String importFilePath = dynamicFields.get(com.xpandit.xray.model.DataParameter.FILEPATH.getKey());

      	 if(importFilePath.contains("../"))
      		throw FormValidation.error("You cannot provide file paths for upper directories.");
   }
    
	@Override
	public BuildStepMonitor getRequiredMonitorService() {
		return BuildStepMonitor.NONE;
	}
	
	
	@Extension
    public static class Descriptor extends BuildStepDescriptor<Publisher> {
        private static long BUILD_STEP_SEED = 0;
        private long buildID;
        private boolean sameExecutionEnabled;

        public Descriptor() {
        	super(XrayImportBuilder.class);
            load();
        }

		public boolean isSameExecutionEnabled() {
			return sameExecutionEnabled;
		}

		@Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            save();
            return true;
        }
        
        @Override
		public XrayImportBuilder newInstance(StaplerRequest req, JSONObject formData){

        	Map<String,String> dynamicFields = getDynamicFields(formData.getJSONObject("dynamicFields"));
			XrayInstance server = getConfiguration(formData.getString("serverInstance"));
			Endpoint endpoint = Endpoint.lookupBySuffix(formData.getString("formatSuffix"));
			
			return new XrayImportBuilder(server,endpoint,dynamicFields);
			
        }
        
        private Map<String,String> getDynamicFields(JSONObject configuredFields){
        	
        	Map<String,String> dynamicFields = new HashMap<String,String>();
        	
        	Set<String> keys = configuredFields.keySet();
        	
        	for(String key : keys){
        		if(configuredFields.containsKey(key)){
        			String value = configuredFields.getString(key);
					if(StringUtils.isNotBlank(value))
						dynamicFields.put(key, value);
        		}
        	}

        	this.sameExecutionEnabled = Objects.equals(dynamicFields.get("sameExecutionCheckbox"), "true") ? true : false;
        	
        	return dynamicFields;
        	
        }
        
        
        private XrayInstance getConfiguration(String configID){
        	XrayInstance config =  null;
        	List<XrayInstance> serverInstances =  getServerInstances();
        	for(XrayInstance sc : serverInstances){
        		if(sc.getConfigID().equals(configID)){
        			config = sc;break;
        		}
        	}
        	return config;
        }
   
        
        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return FreeStyleProject.class.isAssignableFrom(jobType);
        }

        @Override
        public String getDisplayName() {
            return "Xray: Results Import Task";
        }
   
        public ListBoxModel doFillFormatSuffixItems() {
        	
            ListBoxModel items = new ListBoxModel();
            for(Endpoint e : Endpoint.values())
            	items.add(e.getName(), e.getSuffix());
            
            return items;
        }
        
        public ListBoxModel doFillServerInstanceItems() {
        	
            ListBoxModel items = new ListBoxModel();
            List<XrayInstance> serverInstances =  getServerInstances();
            for(XrayInstance sc : serverInstances)
            	items.add(sc.getAlias(),sc.getConfigID());
            
            return items;
        }
        
        public long defaultBuildID(){
        	return buildID;
        }
        
        public void setBuildID(){
        	buildID = ++BUILD_STEP_SEED;
        }
        
        public String defaultFormats(){
            Map<String,FormatBean> formats = new HashMap<String,FormatBean>();
            for(Endpoint e : Endpoint.values()){
            	FormatBean bean = e.toBean();
            	formats.put(e.getSuffix(),bean);
            }
            return gson.toJson(formats);	
        }
        
        public List<XrayInstance> getServerInstances() {
			return ServerConfiguration.get().getServerInstances();
		}
        
    }

}
