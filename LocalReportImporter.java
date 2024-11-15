package com.cgi.msuite.reportrepository.mpower;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.rmi.RemoteException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Properties;

import com.cgi.msuite.reportrepository.SystemConfig;
import com.cgi.msuite.reportrepository.services.IServices;
import com.cgi.msuite.reportrepository.services.IServicesStub;
import com.cgi.msuite.reportrepository.services.ServicesBase;
import com.cgi.msuite.reportrepository.services.IServicesStub.ArrayOfParameter;
import com.cgi.msuite.reportrepository.services.IServicesStub.DoService;
import com.cgi.msuite.reportrepository.services.IServicesStub.DoServiceResponse;
import com.cgi.msuite.utils.password.PasswordUtils;

public class LocalReportImporter {
	private Properties systemConf;
	public static void main(String[] args) {

		LocalReportImporter importer = new LocalReportImporter();
		
		if (args == null || args.length != 1){
			System.out.println("Usage: ReportImporter [Control File Path]");
			System.exit(-1);
			return;
		}
		
		String controlFilePath = args[0];
		File controlFile = new File(controlFilePath);
		if (!controlFile.exists()){
			System.out.println("Error: Report Definition File does't exist. " + controlFilePath);
			System.exit(-1);
			return;
		}
		
		
		try {
			int r = importer.importReport(controlFile, true);
			System.out.println("Result:" + r);
			System.exit(r);
			
		} catch (IOException e) {
			e.printStackTrace();
			System.out.println("Result: -1");
			System.exit(-1);
			return;
		}

	}
	
	public int importReport(File controlFile, boolean createUniqueBatchId) throws IOException{		
		if (!controlFile.exists()){
			return -1;
		}
		
		Properties systemConf = new Properties();
		String confFile = "client-conf.properties";
		if (!SystemConfig.isProductionMode())
			confFile = "client-conf-" + SystemConfig.getConfigId() + ".properties";
		
		InputStream sysFileInputStream = getFileInputStream(confFile);
		systemConf.load(sysFileInputStream);
		
		//read properties file
		String ftpRootFolder = systemConf.getProperty("ftp-root-folder");
		String wsUrl = systemConf.getProperty("web-services-url");
		String providerKey = systemConf.getProperty("provider-key");
		String ftpSubFolder = systemConf.getProperty("ftp-sub-folder");
		
		if (ftpRootFolder == null ||wsUrl == null || providerKey == null || ftpSubFolder == null){
			sysFileInputStream.close();
			return -10;
		}
		
		//read control file
		Properties reportConf = new Properties();
		FileInputStream rptFileInputStream = new FileInputStream(controlFile);
		reportConf.load(rptFileInputStream);
		String batchId = reportConf.getProperty("batch_id");
		if (batchId == null || batchId.trim().length() == 0 || createUniqueBatchId){
			batchId = System.currentTimeMillis()+"_"+PasswordUtils.generateRandomPassword(3);
			reportConf.setProperty("batch_id", batchId);
			rptFileInputStream.close();
			FileOutputStream fos = new FileOutputStream(controlFile);
			reportConf.store(fos, null);
			fos.flush();
			fos.close();
			reportConf = new Properties();
			rptFileInputStream = new FileInputStream(controlFile);
			reportConf.load(rptFileInputStream);
		}
		String reportPath = reportConf.getProperty("location").trim();
		//location = reportPath;
		String reportName = reportConf.getProperty("report_file").trim();
		if (batchId == null || reportPath == null || reportName == null){
			sysFileInputStream.close();
			rptFileInputStream.close();
			return -2;
		}
		
		//make sure batchId not confused with folder name
		batchId = batchId.replace('/', '_');
		batchId = batchId.replace('\\', '_');
		
		String reportFullPath = null;
		if (reportPath.endsWith(File.separator)){
			reportFullPath = reportPath + reportName;
		}else{
			reportFullPath = reportPath + File.separator + reportName;
		}
		
		File report = new File(reportFullPath);
//		System.out.println("report file path=" + reportFullPath);
		if (!report.exists()){
			sysFileInputStream.close();
			rptFileInputStream.close();
			return -3;
		}
		
		//upload report
		String folderName = getFolderName(batchId);
		String serverFolder = folderName + File.separator + "reports";
		LocalUpload localUpload = new LocalUpload();
		File[] localSources = new File[]{controlFile, report};
		if (!localUpload.uploadFile(localSources,ftpRootFolder + File.separator + ftpSubFolder + File.separator + serverFolder)){
			sysFileInputStream.close();
			rptFileInputStream.close();
			return -4;
		}
		
		sysFileInputStream.close();
		rptFileInputStream.close();
		
		//web services
		
		IServicesStub stub = new IServicesStub(wsUrl);
		DoService input = new DoService();
		ArrayOfParameter param = new ArrayOfParameter();
		param.addParameter(makeParameter(IServices.SECURITY_PASS_ID, ServicesBase.SECURITY_PASS, "String"));
		param.addParameter(makeParameter(IServices.PARAMETER_SERVICE_ID, "single-file-batch", "String"));
		param.addParameter(makeParameter("externalBatchId", batchId, "String"));
		param.addParameter(makeParameter("batchSubFolder", folderName, "String"));
		param.addParameter(makeParameter("provider", providerKey, "String"));
		input.setIn0(param);

		try {
			DoServiceResponse response = stub.doService(input);
			IServicesStub.Parameter[] p_response = response.getOut().getParameter();
			for (int i=0; i<p_response.length;i++){
//				System.out.println(p_response[i].getName() + "=" + p_response[i].getValue());
				if (p_response[i].getName().equals("ReturnCode")){
					return Integer.parseInt(p_response[i].getValue());
				}
			}
			
			return -6;
		} catch (RemoteException e) {
			e.printStackTrace();
			 return -5;
		}

	}
	
	private ClassLoader getClassLoader() {
		ClassLoader loader = null;
		loader = Thread.currentThread().getContextClassLoader();
		if (loader == null)
			loader = this.getClass().getClassLoader();

		return loader;
	}
	
	private InputStream getFileInputStream(String fileName) {

		InputStream inputStream = null;
		try {

			inputStream = getClassLoader().getResourceAsStream(regulateClassPath(fileName));
			if (inputStream == null)
				inputStream = this.getClass().getResourceAsStream(regulateClassPath(fileName));
		} catch (Exception e) {
			System.err.println(e.toString());
			return null;
		}

		return inputStream;
	}
	
	private String regulateClassPath(String filePath) {

		if (filePath.startsWith("/")) {
			return filePath;
		} else
			return "/" + filePath;
	}
	
	private String getFolderName(String batchId){
		Date today = new Date();
		SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd-HH-mm-sss");
		return dateFormat.format(today) + "_" + batchId;
	}

	private static IServicesStub.Parameter makeParameter(String name, String value, String type){
		IServicesStub.Parameter p = new IServicesStub.Parameter();
		p.setName(name);
		p.setValue(value);
		p.setType(type);
		return p;
	}
	private Properties getSystemConfig(){
		if (systemConf == null){
			systemConf = new Properties();
			String confFile = "system.properties";
			InputStream rptFileInputStream = getFileInputStream(confFile);
			try {
				systemConf.load(rptFileInputStream);
			} catch (IOException e) {
				e.printStackTrace();
				return null;
			}
		}
		return systemConf;
	}
	private boolean isProductionMode(){
		boolean productionMode = getSystemConfig().getProperty("PRODUCTION_MODE", "false").equalsIgnoreCase("true");
		return productionMode;
	}
	private String getConfigId(){
		return getSystemConfig().getProperty("CONFIG_ID");
	}

}
