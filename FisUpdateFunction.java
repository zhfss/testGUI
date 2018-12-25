// Decompiled by Jad v1.5.8g. Copyright 2001 Pavel Kouznetsov.
// Jad home page: http://www.kpdus.com/jad.html
// Decompiler options: packimports(3) 
// Source File Name:   FisUpdateFunction.java

package com.mt.core.update.updateFunction;

import com.mt.common.Formatter;
import com.mt.common.dynamicDataDef.FieldMap;
import com.mt.common.localStore.BondLocalDB;
import com.mt.common.xml.XMLUtil;
import com.mt.core.update.UpdateFunction;
import com.mt.core.update.UpdateViewer;
import com.mt.util.DateUtil;
import com.mt.util.codec.BinConverter;
import com.mt.util.file.FileUtil;
import java.io.*;
import java.net.*;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.jar.JarOutputStream;
import java.util.jar.Pack200;
import java.util.zip.GZIPInputStream;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.*;
import sun.misc.BASE64Encoder;

public class FisUpdateFunction extends UpdateFunction
{
    /* member class not found */
    class FileDownLoader {}


    public FisUpdateFunction(UpdateViewer updateViewer)
    {
        super(updateViewer);
        count = 0;
        switchUpdateSite = false;
        lwsDays = 30;
        updateInfo = null;
        retry = 0;
        downloaderSet = null;
        poolSize = 10;
    }

    public void setUpdateInfo(FieldMap info)
    {
        updateInfo = info;
    }

    private String getLocalVersionPath()
    {
        return updateInfo != null ? (new StringBuilder()).append("cfg/").append(updateInfo.getStringValue("app")).append("version.xml").toString() : "cfg/version.xml";
    }

    private String getUpdateFile()
    {
        if(updateInfo != null)
        {
            String m = updateInfo.getStringValue("app").toLowerCase();
            m = !m.equals("xms") && !m.equals("fms2") ? m : "fms";
            return (new StringBuilder()).append(m).append("_").append("update.xml").toString();
        } else
        {
            return "update.xml";
        }
    }

    private void initLwsUpdatePara()
    {
        useLWSUpdateSite = false;
        lwsDays = 30;
        try
        {
            Document doc = XMLUtil.createDocumentFromFile("./cfg/LwsUpdate.xml");
            NodeList list = doc.getElementsByTagName("site");
            if(list != null)
            {
                Element element = (Element)list.item(0);
                useLWSUpdateSite = "true".equals(element.getAttribute("useLWSUpdateSite").trim().toLowerCase());
                lwsDays = Formatter.getInt(element.getAttribute("lwsDays").trim());
            }
        }
        catch(Exception e)
        {
            LOG.error(e.toString());
        }
    }

    public void init()
    {
        String fileContent;
        Node localVersionNode;
        Node localServerNode;
        String updateInfo;
        try
        {
            initLwsUpdatePara();
            if(this.updateInfo != null && this.updateInfo.getStringValue("app").trim().equals(""))
            {
                LOG.error("updateInfo.getStringValue(\"app\")\u4E3A\u7A7A\uFF01");
                return;
            }
        }
        catch(Exception e)
        {
            LOG.error("update\u7A0B\u5E8F\u521D\u59CB\u5316\u51FA\u9519", e);
            if(e instanceof RuntimeException)
                throw (RuntimeException)e;
            else
                throw new RuntimeException("update\u7A0B\u5E8F\u521D\u59CB\u5316\u51FA\u9519", e);
        }
        localVersionFile = new File(getLocalVersionPath());
        if(!localVersionFile.exists())
        {
            LOG.error((new StringBuilder()).append("\u627E\u4E0D\u5230\u672C\u5730\u7248\u672C\u4FE1\u606F\u6587\u4EF6\uFF1A").append(getLocalVersionPath()).toString());
            throw new RuntimeException((new StringBuilder()).append("\u627E\u4E0D\u5230\u672C\u5730\u7248\u672C\u4FE1\u606F\u6587\u4EF6\uFF1A").append(getLocalVersionPath()).toString());
        }
        fileContent = FileUtil.readTxtFile(localVersionFile);
        LOG.info("localVersionFileInfo: {}", fileContent);
        localVersionDoc = XMLUtil.createDocument(fileContent);
        localVersionNode = localVersionDoc.getElementsByTagName("version").item(0);
        localVersion = localVersionNode.getTextContent();
        LOG.info("local version: {}", localVersion);
        localServerNode = localVersionDoc.getElementsByTagName("inner_update_site").item(0);
        if(!useLWSUpdateSite || localServerNode == null)
            localServerNode = localVersionDoc.getElementsByTagName("update_site").item(0);
        localServerSite = localServerNode.getTextContent();
        LOG.info("localServerSite: {}", localServerSite);
        updateInfo = readUpdateInfo();
        LOG.info("updateInfo: {}", updateInfo);
        remoteUpdateDoc = XMLUtil.createDocument(updateInfo);
        remoteServerSite = remoteUpdateDoc.getElementsByTagName("update_site").item(0).getTextContent();
        remoteVersion = remoteUpdateDoc.getElementsByTagName("version").item(0).getTextContent();
        LOG.info((new StringBuilder()).append("remote version: ").append(remoteVersion).toString());
        UpdateViewer.VersionStr = localVersion;
        if(remoteVersion.compareTo(localVersion) < 0)
            return;
        if(remoteVersion.equals(localVersion))
            LOG.info("Local and remote version are the same.");
        localVersionNode.setTextContent(remoteVersion);
        count = procCount();
        LOG.info("\u8981\u6267\u884C\u7684\u4EFB\u52A1\u6570 = {}", Integer.valueOf(count));
        downloadLocalStore();
    }

    private String readUpdateInfo()
    {
        try
        {
            InputStream instream = getRemoteData((new StringBuilder()).append(localServerSite).append(getUpdateFile()).toString());
            BufferedReader reader = new BufferedReader(new InputStreamReader(instream, "UTF-8"));
            StringBuffer temps = new StringBuffer();
            String in;
            while((in = reader.readLine()) != null) 
                temps.append((new StringBuilder()).append(in).append("\n").toString());
            instream.close();
            return temps.toString();
        }
        catch(Throwable tb)
        {
            LOG.error("\u8BFB\u53D6\u66F4\u65B0\u4FE1\u606F\u53D1\u751F\u5F02\u5E38", tb);
        }
        try
        {
            Thread.sleep(6000L);
        }
        catch(InterruptedException e)
        {
            LOG.error("\u7EBF\u7A0B\u88AB\u4E2D\u65AD", e);
        }
        retry++;
        if(retry <= 6)
        {
            LOG.info("\u91CD\u65B0\u5C1D\u8BD5\u8BFB\u53D6\u66F4\u65B0\u4FE1\u606F");
            return readUpdateInfo();
        }
        if(useLWSUpdateSite && retry <= 10)
        {
            switchUpdateSite = true;
            Node localServerNode = localVersionDoc.getElementsByTagName("update_site").item(0);
            String switchSite = localServerNode.getTextContent();
            if(!switchSite.equals(localServerSite))
            {
                localServerSite = switchSite;
                LOG.info("\u66F4\u6362\u66F4\u65B0\u7AD9\u70B9\uFF0C\u91CD\u65B0\u5C1D\u8BD5\u8BFB\u53D6\u66F4\u65B0\u4FE1\u606F");
                LOG.info("localServerSite: {}", localServerSite);
                return readUpdateInfo();
            } else
            {
                throw new RuntimeException("\u5F88\u62B1\u6B49,\u7ECF\u591A\u6B21\u5C1D\u8BD5\u540E\u4ECD\u7136\u65E0\u6CD5\u8BFB\u53D6\u66F4\u65B0\u4FE1\u606F");
            }
        } else
        {
            throw new RuntimeException("\u5F88\u62B1\u6B49,\u7ECF\u591A\u6B21\u5C1D\u8BD5\u540E\u4ECD\u7136\u65E0\u6CD5\u8BFB\u53D6\u66F4\u65B0\u4FE1\u606F");
        }
    }

    private int procCount()
    {
        if(remoteVersion.compareTo(localVersion) < 0)
            return 0;
        int count = 0;
        int id = 0;
        downloaderSet = new LinkedHashSet();
        NodeList addList = remoteUpdateDoc.getElementsByTagName("add");
        int addSize = addList.getLength();
        for(int i = 0; i < addSize; i++)
        {
            Node node = addList.item(i);
            String addFilePath = node.getTextContent().trim();
            String remoteMd5 = node.getAttributes().getNamedItem("md5").getTextContent();
            if(remoteVersion.equals(localVersion) && remoteMd5.trim().equals(""))
                continue;
            File localFile = new File(addFilePath);
            if(localFile.exists())
            {
                String localMd5 = getLocalFileMD5(localFile);
                if(remoteMd5.equalsIgnoreCase(localMd5) || addFilePath.equals("cfg/OTSSetting.xml"))
                    continue;
                File localFileTmp = new File((new StringBuilder()).append(addFilePath).append(".tmp").toString());
                if(localFileTmp.exists() && remoteMd5.equalsIgnoreCase(getLocalFileMD5(localFileTmp)))
                    continue;
                if(LOG.isDebugEnabled())
                {
                    LOG.debug((new StringBuilder()).append(localFile.getAbsolutePath()).append(" MD5 different. Add it to joblist.").toString());
                    LOG.debug(String.format("R MD5: %s", new Object[] {
                        remoteMd5
                    }));
                    LOG.debug(String.format("L MD5: %s", new Object[] {
                        localMd5
                    }));
                }
            }
            if(LOG.isDebugEnabled())
                LOG.debug((new StringBuilder()).append(localFile.getAbsolutePath()).append("doesn't exist. Add it to joblist.").toString());
            downloaderSet.add(new FileDownLoader(id++, localServerSite, addFilePath, remoteMd5));
        }

        count += downloaderSet.size();
        if(remoteVersion.equals(localVersion) && count == 0)
        {
            return 0;
        } else
        {
            count += remoteUpdateDoc.getElementsByTagName("del").getLength();
            return count;
        }
    }

    public int count()
    {
        return count;
    }

    public void download()
    {
        NodeList deleteList = remoteUpdateDoc.getElementsByTagName("del");
        int deleteSize = deleteList.getLength();
        LOG.info("\u4EA7\u751F.delete\u6587\u4EF6. \u4EFB\u52A1\u603B\u6570 = {}", Integer.valueOf(deleteSize));
        for(int i = 0; i < deleteSize; i++)
        {
            Node node = deleteList.item(i);
            String deleteFilePath = node.getTextContent().trim();
            File targetFile = new File(deleteFilePath);
            if(targetFile.exists() && targetFile.isFile())
                try
                {
                    String dPath = targetFile.getAbsolutePath();
                    File dFile = new File((new StringBuilder()).append(dPath).append(".delete").toString());
                    if(!dFile.exists())
                        FileUtil.copyFile(dPath, (new StringBuilder()).append(dPath).append(".delete").toString(), true);
                }
                catch(IOException e)
                {
                    throw new RuntimeException((new StringBuilder()).append("\u751F\u6210.delete\u6587\u4EF6\u5931\u8D25:").append(deleteFilePath).toString(), e);
                }
            updateViewer.makeProgressValue();
        }

        if(downloaderSet.size() == 0)
        {
            LOG.info("\u6CA1\u6709\u4EFB\u4F55\u4E1C\u897F\u8981download,\u66F4\u65B0\u5B8C\u6210.");
            return;
        }
        try
        {
            LOG.info("\u542F\u52A8\u7EBF\u7A0B\u6C60\u6267\u884Cdownload\u4EFB\u52A1.");
            FileDownLoader fileDownLoader;
            for(Iterator i$ = downloaderSet.iterator(); i$.hasNext(); LOG.info((new StringBuilder()).append("\u66F4\u65B0\u4EFB\u52A1:").append(FileDownLoader.access._mth000(fileDownLoader)).append("|").append(FileDownLoader.access._mth100(fileDownLoader)).append("|").append(FileDownLoader.access._mth200(fileDownLoader)).toString()))
                fileDownLoader = (FileDownLoader)i$.next();

            ExecutorService executor = Executors.newFixedThreadPool(poolSize);
            List results = executor.invokeAll(downloaderSet);
            Future future;
            for(Iterator i$ = results.iterator(); i$.hasNext(); future.get())
                future = (Future)i$.next();

        }
        catch(Exception e)
        {
            LOG.error("\u676F\u5177\u4E86...", e);
            throw new RuntimeException("\u7EBF\u7A0B\u6C60\u53D1\u751F\u5F02\u5E38");
        }
        LOG.info("\u66F4\u65B0updater.jar");
        try
        {
            File uTmp = new File("./dist/updater.jar.tmp");
            File upJar = new File("./dist/updater.jar");
            if(uTmp.exists())
                FileUtils.copyFile(uTmp, new File("./updater.jar"));
            else
            if(upJar.exists())
                FileUtils.copyFile(upJar, new File("./updater.jar"));
            else
                LOG.warn("\u5C1D\u8BD5\u66F4\u65B0updater.jar\u65F6\u627E\u4E0D\u5230dist\u76EE\u5F55\u6216\u662Fupdater.jar\u6863\u6848");
        }
        catch(Exception e)
        {
            LOG.error("\u66F4\u65B0updater.jar\u65F6\u53D1\u751F\u95EE\u9898", e);
            throw new RuntimeException("\u66F4\u65B0updater.jar\u65F6\u53D1\u751F\u5F02\u5E38");
        }
        LOG.info("\u66F4\u65B0\u672C\u5730\u7248\u672C\u4FE1\u606F");
        try
        {
            updateLocalInfo();
        }
        catch(Exception e)
        {
            LOG.error("update\u7A0B\u5E8F\u4FEE\u6539\u672C\u5730\u7248\u672C\u4FE1\u606F\u51FA\u9519", e);
            throw new RuntimeException("update\u7A0B\u5E8F\u4FEE\u6539\u672C\u5730\u7248\u672C\u4FE1\u606F\u51FA\u9519", e);
        }
        UpdateViewer.VersionStr = remoteVersion;
    }

    private void updateLocalInfo()
        throws Exception
    {
        String template = "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n<VersionInfo>\n\t<update_site>%s</update_site>\n\t<version>%s</version>\n</VersionInfo>";
        StringBuffer versionBuf = new StringBuffer();
        versionBuf.append("<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n");
        versionBuf.append("<VersionInfo>\n");
        Node localServerNode_Inner = localVersionDoc.getElementsByTagName("inner_update_site").item(0);
        if(useLWSUpdateSite)
        {
            if(localServerNode_Inner != null)
            {
                String inner_update_site = localServerNode_Inner.getTextContent();
                versionBuf.append((new StringBuilder()).append("\t<inner_update_site>").append(inner_update_site).append("</inner_update_site>\n").toString());
            }
            Node localServerNode = localVersionDoc.getElementsByTagName("update_site").item(0);
            if(localServerNode != null)
            {
                String update_site = remoteServerSite;
                versionBuf.append((new StringBuilder()).append("\t<update_site>").append(update_site).append("</update_site>\n").toString());
            }
        } else
        {
            if(localServerNode_Inner != null)
            {
                String inner_update_site = localServerNode_Inner.getTextContent();
                versionBuf.append((new StringBuilder()).append("\t<inner_update_site>").append(inner_update_site).append("</inner_update_site>\n").toString());
            }
            versionBuf.append((new StringBuilder()).append("\t<update_site>").append(remoteServerSite).append("</update_site>\n").toString());
        }
        versionBuf.append("\t<version>");
        versionBuf.append(remoteVersion);
        versionBuf.append("</version>\n");
        versionBuf.append("</VersionInfo>");
        String data = versionBuf.toString();
        File file = new File("./cfg/versiontmp");
        if(!file.exists())
            file.mkdir();
        String vf = updateInfo != null ? (new StringBuilder()).append("./cfg/versiontmp/").append(updateInfo.getStringValue("app")).toString() : "./cfg/versiontmp/FIS";
        LOG.info(String.format("\u5199\u56DE%s\n%s", new Object[] {
            vf, data
        }));
        FileUtils.writeStringToFile(new File(vf), data, "UTF-8");
    }

    private InputStream getRemoteData(String webPath)
        throws Exception
    {
        String urlString = encodeURL(webPath, "UTF-8");
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection)url.openConnection();
        conn.setDefaultUseCaches(false);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", "Mozilla/4.0 (compatible; MSIE 6.0; Windows NT 5.1; SV1; .NET CLR 1.1.4322; .NET CLR 1.0.3705)");
        conn.setRequestProperty("Authorization", (new StringBuilder()).append("Basic ").append((new BASE64Encoder()).encode("mtupdater:Moderntimes2009".getBytes("UTF-8"))).toString());
        return new BufferedInputStream(conn.getInputStream());
    }

    private String getLocalFileMD5(File localFile)
    {
        if(localFile == null)
        {
            LOG.error("\u9519\u8BEF:\u6CA1\u6709\u6307\u5B9A\u6587\u4EF6");
            return "ERROR.";
        }
        if(!localFile.exists() || !localFile.isFile())
        {
            LOG.error((new StringBuilder()).append("\u9519\u8BEF:\u6307\u5B9A\u6587\u4EF6\u4E0D\u5B58\u5728\u6216\u4E0D\u662F\u6863\u6848").append(localFile.getAbsolutePath()).toString());
            return "ERROR.";
        }
        try
        {
            MessageDigest md = MessageDigest.getInstance("MD5");
            FileInputStream fis = new FileInputStream(localFile);
            DigestInputStream dis = new DigestInputStream(fis, md);
            BufferedInputStream inStr = new BufferedInputStream(dis);
            int fileSize = (int)localFile.length();
            byte b[] = new byte[fileSize];
            inStr.read(b);
            fis.close();
            return BinConverter.bytesToBinHex(md.digest()).toUpperCase();
        }
        catch(Exception e)
        {
            LOG.error((new StringBuilder()).append("\u6587\u4EF6MD5\u8BA1\u7B97\u9519\u8BEF:").append(localFile.getAbsolutePath()).toString(), e);
        }
        return "ERROR.";
    }

    private static String encodeURL(String url, String encode)
        throws UnsupportedEncodingException
    {
        StringBuilder sb = new StringBuilder();
        StringBuilder noAsciiPart = new StringBuilder();
        for(int i = 0; i < url.length(); i++)
        {
            char c = url.charAt(i);
            if(c > '\377')
            {
                noAsciiPart.append(c);
                continue;
            }
            if(noAsciiPart.length() != 0)
            {
                sb.append(URLEncoder.encode(noAsciiPart.toString(), encode));
                noAsciiPart.delete(0, noAsciiPart.length());
            }
            sb.append(c);
        }

        return sb.toString();
    }

    private boolean downloadFile(String servStr, String filePath, String declaredMD5)
        throws Exception
    {
        int retryCount;
        DigestInputStream dis;
        BufferedOutputStream out;
        Exception cause;
        boolean downloadFail;
        retryCount = 0;
        LOG.info((new StringBuilder()).append("filePath=====").append(filePath).toString());
        if(filePath.endsWith(".jar") && !filePath.startsWith("lib"))
        {
            boolean success;
            for(success = false; !(success = downloadAndUnpackFile(servStr, filePath, declaredMD5)) && ++retryCount <= 6;)
                try
                {
                    System.gc();
                    Thread.sleep(1000L);
                }
                catch(InterruptedException e)
                {
                    LOG.error("", e);
                }

            if(success)
            {
                if(LOG.isDebugEnabled())
                    LOG.debug("The new GUNZIP & unpack procedure was done successfully.");
                return true;
            }
            LOG.info("The new GUNZIP & unpack procedure failed. Go to the old procedure.");
        }
        retryCount = 0;
        dis = null;
        out = null;
        cause = null;
        downloadFail = false;
_L2:
        File newFile;
        String actualMD5;
        boolean flag;
        String url = (new StringBuilder()).append(servStr).append(filePath).toString();
        InputStream remoteInputStream = getRemoteData(url);
        newFile = null;
        if(filePath.endsWith(".delete"))
            newFile = new File(filePath);
        else
            newFile = new File((new StringBuilder()).append(filePath).append(".tmp").toString());
        if(!newFile.exists())
        {
            String loc = newFile.getPath();
            LOG.info(String.format("the path:[%s] doesn't exist. create it.", new Object[] {
                loc
            }));
            int pos = loc.lastIndexOf(File.separator, loc.length() - 1 - 1);
            if(pos != -1)
            {
                String path = loc.substring(0, pos);
                File pf = new File(path);
                pf.mkdirs();
            }
        } else
        {
            newFile.delete();
        }
        MessageDigest md = MessageDigest.getInstance("MD5");
        dis = new DigestInputStream(remoteInputStream, md);
        out = new BufferedOutputStream(new FileOutputStream(newFile));
        LOG.info(String.format("downloading [%s]", new Object[] {
            filePath
        }));
        int i = 0;
        byte buf[] = new byte[0x80000];
        while((i = dis.read(buf)) > 0) 
            out.write(buf, 0, i);
        LOG.info(String.format("Downloaded : %s, fileLength: %12d", new Object[] {
            newFile.getPath(), Long.valueOf(newFile.length())
        }));
        actualMD5 = BinConverter.bytesToBinHex(md.digest());
        if(!declaredMD5.equals(""))
            break MISSING_BLOCK_LABEL_585;
        LOG.info((new StringBuilder()).append(newFile.getName()).append(" downloaded but not checked because declared MD5 is empty string.").toString());
        flag = true;
        if(dis != null)
            try
            {
                dis.close();
            }
            catch(Exception ex)
            {
                LOG.error("\u5173\u95ED\u6D41\u53D1\u751F\u5F02\u5E38", ex);
            }
        if(out != null)
            try
            {
                out.close();
            }
            catch(Exception ex)
            {
                LOG.error("\u5173\u95ED\u6D41\u53D1\u751F\u5F02\u5E38", ex);
            }
        return flag;
        if(!actualMD5.equalsIgnoreCase(declaredMD5))
        {
            LOG.warn(String.format("%s MD5: %s, declared MD5: %s", new Object[] {
                newFile.getName(), actualMD5, declaredMD5
            }));
            LOG.warn((new StringBuilder()).append(newFile.getName()).append(" downloaded and checked failure.").toString());
            downloadFail = true;
            break MISSING_BLOCK_LABEL_831;
        }
        if(LOG.isDebugEnabled())
        {
            LOG.debug(String.format("%s MD5: %s", new Object[] {
                newFile.getName(), actualMD5
            }));
            LOG.debug((new StringBuilder()).append("Declared MD5: ").append(declaredMD5).toString());
        }
        LOG.info((new StringBuilder()).append(newFile.getName()).append(" downloaded and checked OK.").toString());
        flag = true;
        if(dis != null)
            try
            {
                dis.close();
            }
            catch(Exception ex)
            {
                LOG.error("\u5173\u95ED\u6D41\u53D1\u751F\u5F02\u5E38", ex);
            }
        if(out != null)
            try
            {
                out.close();
            }
            catch(Exception ex)
            {
                LOG.error("\u5173\u95ED\u6D41\u53D1\u751F\u5F02\u5E38", ex);
            }
        return flag;
        if(dis != null)
            try
            {
                dis.close();
            }
            catch(Exception ex)
            {
                LOG.error("\u5173\u95ED\u6D41\u53D1\u751F\u5F02\u5E38", ex);
            }
        if(out != null)
            try
            {
                out.close();
            }
            catch(Exception ex)
            {
                LOG.error("\u5173\u95ED\u6D41\u53D1\u751F\u5F02\u5E38", ex);
            }
        break MISSING_BLOCK_LABEL_1049;
        Exception e;
        e;
        cause = e;
        LOG.error((new StringBuilder()).append("\u66F4\u65B0\u6587\u4EF6\u5931\u8D25:").append(filePath).toString(), e);
        downloadFail = true;
        if(dis != null)
            try
            {
                dis.close();
            }
            catch(Exception ex)
            {
                LOG.error("\u5173\u95ED\u6D41\u53D1\u751F\u5F02\u5E38", ex);
            }
        if(out != null)
            try
            {
                out.close();
            }
            catch(Exception ex)
            {
                LOG.error("\u5173\u95ED\u6D41\u53D1\u751F\u5F02\u5E38", ex);
            }
        break MISSING_BLOCK_LABEL_1049;
        Exception exception;
        exception;
        if(dis != null)
            try
            {
                dis.close();
            }
            catch(Exception ex)
            {
                LOG.error("\u5173\u95ED\u6D41\u53D1\u751F\u5F02\u5E38", ex);
            }
        if(out != null)
            try
            {
                out.close();
            }
            catch(Exception ex)
            {
                LOG.error("\u5173\u95ED\u6D41\u53D1\u751F\u5F02\u5E38", ex);
            }
        throw exception;
        try
        {
            System.gc();
            Thread.sleep(1000L);
        }
        // Misplaced declaration of an exception variable
        catch(Exception ex)
        {
            LOG.warn("Exception when sleeping", ex);
        }
        retryCount++;
        if(!downloadFail || retryCount > 6)
            if(cause != null)
            {
                throw cause;
            } else
            {
                LOG.warn("There exists a flaw in downloadFile() logic if the program runs here.");
                return false;
            }
        if(true) goto _L2; else goto _L1
_L1:
    }

    private boolean downloadAndUnpackFile(String servStr, String filePath, String declaredMD5)
    {
        InputStream remoteInputStream;
        GZIPInputStream gis;
        ByteArrayOutputStream out;
        ByteArrayInputStream in;
        JarOutputStream jostream;
        String url = (new StringBuilder()).append(servStr).append(filePath).append(".pack.gzip").toString();
        LOG.info(String.format("downloading START : [%s.pack.gzip]", new Object[] {
            filePath
        }));
        remoteInputStream = getRemoteData(url);
        LOG.info(String.format("downloading and gunzip [%s.pack.gzip]", new Object[] {
            filePath
        }));
        gis = null;
        out = null;
        in = null;
        jostream = null;
        gis = new GZIPInputStream(remoteInputStream);
        out = new ByteArrayOutputStream(0x40000);
        byte buf[] = new byte[0x10000];
        for(int i = 0; (i = gis.read(buf)) > 0;)
            out.write(buf, 0, i);

        out.flush();
        in = new ByteArrayInputStream(out.toByteArray());
        LOG.info(String.format("unpacking [%s.pack]", new Object[] {
            filePath
        }));
        File tmpF = new File((new StringBuilder()).append(filePath).append(".tmp").toString());
        if(tmpF.exists())
            tmpF.delete();
        jostream = new JarOutputStream(new FileOutputStream(tmpF));
        java.util.jar.Pack200.Unpacker unpacker = Pack200.newUnpacker();
        unpacker.unpack(in, jostream);
        jostream.close();
        if(gis != null)
            try
            {
                gis.close();
            }
            catch(Exception ex)
            {
                LOG.error("\u5173\u95ED\u6D41\u53D1\u751F\u5F02\u5E38", ex);
            }
        if(out != null)
            try
            {
                out.close();
            }
            catch(Exception ex)
            {
                LOG.error("\u5173\u95ED\u6D41\u53D1\u751F\u5F02\u5E38", ex);
            }
        if(in != null)
            try
            {
                in.close();
            }
            catch(Exception ex)
            {
                LOG.error("\u5173\u95ED\u6D41\u53D1\u751F\u5F02\u5E38", ex);
            }
        if(jostream != null)
            try
            {
                jostream.close();
            }
            catch(Exception ex)
            {
                LOG.error("\u5173\u95ED\u6D41\u53D1\u751F\u5F02\u5E38", ex);
            }
        break MISSING_BLOCK_LABEL_654;
        Exception e;
        e;
        boolean flag;
        LOG.error((new StringBuilder()).append("gunzip & unpack\u53D1\u751F\u5F02\u5E38:").append(filePath).toString(), e);
        flag = false;
        if(gis != null)
            try
            {
                gis.close();
            }
            catch(Exception ex)
            {
                LOG.error("\u5173\u95ED\u6D41\u53D1\u751F\u5F02\u5E38", ex);
            }
        if(out != null)
            try
            {
                out.close();
            }
            catch(Exception ex)
            {
                LOG.error("\u5173\u95ED\u6D41\u53D1\u751F\u5F02\u5E38", ex);
            }
        if(in != null)
            try
            {
                in.close();
            }
            catch(Exception ex)
            {
                LOG.error("\u5173\u95ED\u6D41\u53D1\u751F\u5F02\u5E38", ex);
            }
        if(jostream != null)
            try
            {
                jostream.close();
            }
            catch(Exception ex)
            {
                LOG.error("\u5173\u95ED\u6D41\u53D1\u751F\u5F02\u5E38", ex);
            }
        return flag;
        Exception exception;
        exception;
        if(gis != null)
            try
            {
                gis.close();
            }
            catch(Exception ex)
            {
                LOG.error("\u5173\u95ED\u6D41\u53D1\u751F\u5F02\u5E38", ex);
            }
        if(out != null)
            try
            {
                out.close();
            }
            catch(Exception ex)
            {
                LOG.error("\u5173\u95ED\u6D41\u53D1\u751F\u5F02\u5E38", ex);
            }
        if(in != null)
            try
            {
                in.close();
            }
            catch(Exception ex)
            {
                LOG.error("\u5173\u95ED\u6D41\u53D1\u751F\u5F02\u5E38", ex);
            }
        if(jostream != null)
            try
            {
                jostream.close();
            }
            catch(Exception ex)
            {
                LOG.error("\u5173\u95ED\u6D41\u53D1\u751F\u5F02\u5E38", ex);
            }
        throw exception;
        File tmpFile;
        String actualMD5;
        tmpFile = new File((new StringBuilder()).append(filePath).append(".tmp").toString());
        actualMD5 = getLocalFileMD5(tmpFile);
        if(declaredMD5.equals(""))
        {
            LOG.info((new StringBuilder()).append(tmpFile.getName()).append(" downloaded but not checked because declared MD5 is empty string. [GUNZIP & unpack]").toString());
            return true;
        }
        if(!actualMD5.equalsIgnoreCase(declaredMD5))
        {
            LOG.warn(String.format("%s MD5: %s, declared MD5: %s", new Object[] {
                tmpFile.getName(), actualMD5, declaredMD5
            }));
            LOG.warn((new StringBuilder()).append(tmpFile.getName()).append(" downloaded and checked failure. [GUNZIP & unpack]").toString());
            return false;
        }
        try
        {
            if(LOG.isDebugEnabled())
            {
                LOG.debug(String.format("%s MD5: %s", new Object[] {
                    tmpFile.getName(), actualMD5
                }));
                LOG.debug((new StringBuilder()).append("Declared MD5: ").append(declaredMD5).toString());
            }
            LOG.info((new StringBuilder()).append(tmpFile.getName()).append(" downloaded and checked OK. [GUNZIP & unpack]").toString());
            return true;
        }
        catch(Exception e)
        {
            LOG.error("", e);
        }
        return false;
    }

    private void downloadLocalStore()
    {
        String servStr;
        List list;
        LOG.info("\u5168\u91CF\u540C\u6B65\u843D\u5730\u670D\u52A1\u5668\u7684localStore");
        Node localServerNode_Inner = localVersionDoc.getElementsByTagName("inner_update_site").item(0);
        if(!useLWSUpdateSite || localServerNode_Inner == null)
            return;
        String update_site = localServerNode_Inner.getTextContent();
        if(!localServerSite.equals(update_site))
            return;
        String lsInfo = "";
        servStr = "";
        try
        {
            servStr = localServerSite;
            servStr = servStr.replace("download", "localStore");
            String updatePath = (new StringBuilder()).append(servStr).append("localStore.xml").toString();
            InputStream instream = getRemoteData(updatePath);
            BufferedReader reader = new BufferedReader(new InputStreamReader(instream, "UTF-8"));
            StringBuffer temps = new StringBuffer();
            String in;
            while((in = reader.readLine()) != null) 
                temps.append((new StringBuilder()).append(in).append("\n").toString());
            instream.close();
            lsInfo = temps.toString();
            LOG.info("\u5168\u91CF\u843D\u5730\u670D\u52A1\u5668\u7684localStore \u66F4\u65B0\u6587\u4EF6\u4FE1\u606F {}", lsInfo);
        }
        catch(Exception e)
        {
            LOG.error("\u843D\u5730\u670D\u52A1\u5668LocalStore\u66F4\u65B0\u5217\u8868\u83B7\u53D6\u5931\u8D25", e);
            return;
        }
        BondLocalDB.open();
        Date oldUpdateDate = BondLocalDB.getBondTimestamp("FISCommonBondUpdateDate");
        BondLocalDB.close();
        list = new ArrayList();
        Document remoteLSDoc;
        NodeList dateList;
        String remoteUpdateDateStr;
        Date remoteUpdateDate;
        NodeList addList;
        int addSize;
        int i;
        Node node;
        String addFilePath;
        try
        {
            remoteLSDoc = XMLUtil.createDocument(lsInfo);
            dateList = remoteLSDoc.getElementsByTagName("date");
            if(dateList == null || dateList.getLength() <= 0)
            {
                LOG.info("\u843D\u5730\u670D\u52A1\u5668localStore.xml \u65E0\u66F4\u65B0\u65E5\u671F");
                return;
            }
        }
        catch(Exception e)
        {
            LOG.error("", e);
            return;
        }
        remoteUpdateDateStr = dateList.item(0).getTextContent().trim();
        if("".equals(remoteUpdateDateStr))
        {
            LOG.info("\u843D\u5730\u670D\u52A1\u5668localStore.xml \u66F4\u65B0\u65E5\u671Fdate\u4E3A\u7A7A\uFF0C \u4E0D\u505A\u66F4\u65B0");
            return;
        }
        remoteUpdateDate = DateUtil.getDate(remoteUpdateDateStr, "yyyyMMdd");
        if(oldUpdateDate == null && lwsDays > 10000)
        {
            LOG.info("\u672C\u5730localStore  \u66F4\u65B0\u65E5\u671Fdate \u4E3A\u7A7A\uFF0C\u800C\u4E14\u914D\u7F6E\u6587\u4EF6\u5929\u6570\u5927\u4E8E10000\u65F6\uFF0C \u4E0D\u505A\u66F4\u65B0");
            return;
        }
        if(oldUpdateDate != null && DateUtil.getDaysBetween(oldUpdateDate, remoteUpdateDate) < lwsDays)
        {
            LOG.info("\u672C\u5730localStore \u4E0E\u843D\u5730\u670D\u52A1\u5668localStore \u66F4\u65B0\u65E5\u671Fdate \u5DEE\u5C0F\u4E8E\u914D\u7F6E\u6587\u4EF6\u5929\u6570\uFF0C \u4E0D\u505A\u66F4\u65B0");
            return;
        }
        addList = remoteLSDoc.getElementsByTagName("filename");
        addSize = addList.getLength();
        for(i = 0; i < addSize; i++)
        {
            node = addList.item(i);
            addFilePath = node.getTextContent().trim();
            list.add(addFilePath);
            LOG.info((new StringBuilder()).append("filePath=====").append(addFilePath).toString());
        }

        try
        {
            for(int i = 0; i < list.size(); i++)
            {
                String filePath = (String)list.get(i);
                String url = (new StringBuilder()).append(servStr).append(filePath).toString();
                InputStream remoteInputStream = getRemoteData(url);
                downloadFileForLocalStore(servStr, filePath, "");
                LOG.info((new StringBuilder()).append("filePath=====").append(filePath).toString());
            }

        }
        catch(Exception e)
        {
            LOG.error("download localStore failed", e);
        }
        return;
    }

    private boolean downloadFileForLocalStore(String servStr, String filePath, String declaredMD5)
        throws Exception
    {
        int retryCount;
        DigestInputStream dis;
        BufferedOutputStream out;
        Exception cause;
        boolean downloadFail;
        retryCount = 0;
        LOG.info((new StringBuilder()).append("filePath=====").append(filePath).toString());
        retryCount = 0;
        dis = null;
        out = null;
        cause = null;
        downloadFail = false;
_L2:
        String url = (new StringBuilder()).append(servStr).append(filePath).toString();
        url = encodeURL(url, "UTF-8");
        InputStream remoteInputStream = getRemoteData(url);
        File newFile = null;
        String prefix = "localStore/";
        newFile = new File((new StringBuilder()).append(prefix).append(filePath).toString());
        if(!newFile.exists())
        {
            String loc = newFile.getPath();
            LOG.info(String.format("the path:[%s] doesn't exist. create it.", new Object[] {
                loc
            }));
            int pos = loc.lastIndexOf(File.separator, loc.length() - 1 - 1);
            if(pos != -1)
            {
                String path = loc.substring(0, pos);
                File pf = new File(path);
                pf.mkdirs();
            }
        } else
        {
            newFile.delete();
        }
        MessageDigest md = MessageDigest.getInstance("MD5");
        dis = new DigestInputStream(remoteInputStream, md);
        out = new BufferedOutputStream(new FileOutputStream(newFile));
        LOG.info(String.format("downloading [%s]", new Object[] {
            filePath
        }));
        int i = 0;
        byte buf[] = new byte[0x80000];
        while((i = dis.read(buf)) > 0) 
            out.write(buf, 0, i);
        LOG.info(String.format("Downloaded : %s, fileLength: %12d", new Object[] {
            newFile.getPath(), Long.valueOf(newFile.length())
        }));
        if(dis != null)
            try
            {
                dis.close();
            }
            catch(Exception ex)
            {
                LOG.error("\u5173\u95ED\u6D41\u53D1\u751F\u5F02\u5E38", ex);
            }
        if(out != null)
            try
            {
                out.close();
            }
            catch(Exception ex)
            {
                LOG.error("\u5173\u95ED\u6D41\u53D1\u751F\u5F02\u5E38", ex);
            }
        break MISSING_BLOCK_LABEL_565;
        Exception e;
        e;
        cause = e;
        LOG.error((new StringBuilder()).append("\u66F4\u65B0\u6587\u4EF6\u5931\u8D25:").append(filePath).toString(), e);
        downloadFail = true;
        if(dis != null)
            try
            {
                dis.close();
            }
            catch(Exception ex)
            {
                LOG.error("\u5173\u95ED\u6D41\u53D1\u751F\u5F02\u5E38", ex);
            }
        if(out != null)
            try
            {
                out.close();
            }
            catch(Exception ex)
            {
                LOG.error("\u5173\u95ED\u6D41\u53D1\u751F\u5F02\u5E38", ex);
            }
        break MISSING_BLOCK_LABEL_565;
        Exception exception;
        exception;
        if(dis != null)
            try
            {
                dis.close();
            }
            catch(Exception ex)
            {
                LOG.error("\u5173\u95ED\u6D41\u53D1\u751F\u5F02\u5E38", ex);
            }
        if(out != null)
            try
            {
                out.close();
            }
            catch(Exception ex)
            {
                LOG.error("\u5173\u95ED\u6D41\u53D1\u751F\u5F02\u5E38", ex);
            }
        throw exception;
        try
        {
            System.gc();
            Thread.sleep(1000L);
        }
        // Misplaced declaration of an exception variable
        catch(Exception ex)
        {
            LOG.warn("Exception when sleeping", ex);
        }
        retryCount++;
        if(!downloadFail || retryCount > 6)
            if(cause != null)
            {
                throw cause;
            } else
            {
                LOG.warn("There exists a flaw in downloadFile() logic if the program runs here.");
                return false;
            }
        if(true) goto _L2; else goto _L1
_L1:
    }

    private static final Logger LOG = LoggerFactory.getLogger(com/mt/core/update/updateFunction/FisUpdateFunction);
    private static final String LOCAL_VERSION_PATH = "cfg/version.xml";
    private static final String UPDATE_FILE = "update.xml";
    private static final String ADD_TAIL = ".tmp";
    private static final String DELETE_TAIL = ".delete";
    private File localVersionFile;
    private Document localVersionDoc;
    private Document remoteUpdateDoc;
    private String localVersion;
    private String localServerSite;
    private String remoteServerSite;
    private String remoteVersion;
    private int count;
    private boolean switchUpdateSite;
    private static boolean useLWSUpdateSite = false;
    private int lwsDays;
    private final int netTimeout = 30;
    private final int retryLimit = 6;
    private FieldMap updateInfo;
    private int retry;
    private Set downloaderSet;
    private int poolSize;
    private int hhh


}
