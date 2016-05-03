package com.couchmate.teamcity.phabricator;

import jetbrains.buildServer.log.Loggers;
import jetbrains.buildServer.serverSide.BuildStatisticsOptions;
import jetbrains.buildServer.serverSide.SRunningBuild;
import jetbrains.buildServer.serverSide.STest;
import jetbrains.buildServer.serverSide.STestRun;
import jetbrains.buildServer.tests.TestInfo;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.commons.io.IOUtils;
import javax.net.ssl.SSLContext;
import java.security.cert.X509Certificate;
import java.security.cert.CertificateException;
import org.apache.http.conn.socket.*;
import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import org.apache.http.conn.ssl.SSLContextBuilder;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.config.Registry;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BuildTracker implements Runnable {

    private SRunningBuild build;
    private AppConfig appConfig;
    private Map<String, STest> tests;

    public BuildTracker(SRunningBuild build){
        this.build = build;
        this.appConfig = new AppConfig();
        this.tests = new HashMap<>();
        Loggers.SERVER.info("Tracking build" + build.getBuildNumber());
    }

    public void run(){
        while (!build.isFinished()){
            if(!appConfig.isEnabled()){
                try{
                    Map<String, String> params = new HashMap<>();
                    params.putAll(this.build.getBuildOwnParameters());

                    if(!this.build.getBuildFeaturesOfType("phabricator").isEmpty())
                        params.putAll(this.build.getBuildFeaturesOfType("phabricator").iterator().next().getParameters());
                    for(String param : params.keySet())
                        if(param != null) Loggers.AGENT.info(String.format("Found %s", param));
                    this.appConfig.setParams(params);
                    this.appConfig.parse();
                } catch (Exception e) { Loggers.SERVER.error("BuildTracker Param Parse", e); }
            } else {
                build
                        .getBuildStatistics(BuildStatisticsOptions.ALL_TESTS_NO_DETAILS)
                        .getAllTests()
                        .forEach(
                                testRun -> {
                                    if(!this.tests.containsKey(testRun.getTest().getName().getAsString())) {
                                        this.tests.put(testRun.getTest().getName().getAsString(),
                                                testRun.getTest());
                                        sendTestReport(testRun.getTest().getName().getAsString(),
                                                testRun);
                                    }

                                }
                        );
            }
        }
        Loggers.SERVER.info(this.build.getBuildNumber() + " finished");
    }

    private void sendTestReport(String testName, STestRun test) {
        HttpRequestBuilder httpPost = new HttpRequestBuilder()
                .post()
                .setHost(this.appConfig.getPhabricatorUrl())
                .setScheme(this.appConfig.getPhabricatorProtocol())
                .setPath("/api/harbormaster.sendmessage")
                        //.setBody(payload.toString())
                .addFormParam(new StringKeyValue("api.token", this.appConfig.getConduitToken()))
                .addFormParam(new StringKeyValue("buildTargetPHID", this.appConfig.getHarbormasterTargetPHID()))
                .addFormParam(new StringKeyValue("type", "work"))
                .addFormParam(new StringKeyValue("unit[0][name]", test.getTest().getName().getTestMethodName()))
                //.addFormParam(new StringKeyValue("unit[0][duration]", String.valueOf(test.getDuration())))
                .addFormParam(new StringKeyValue("unit[0][namespace]", test.getTest().getName().getClassName()));

        if(test.getStatus().isSuccessful()){
            httpPost.addFormParam(new StringKeyValue("unit[0][result]", "pass"));
        } else if (test.getStatus().isFailed()){
            httpPost.addFormParam(new StringKeyValue("unit[0][result]", "fail"));
        }
    try {
    SSLContext sslContext = new SSLContextBuilder().loadTrustMaterial(null, new TrustSelfSignedStrategy() {
                @Override
                public boolean isTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    return true;
                }
        }).build();
    SSLConnectionSocketFactory sslConnectionFactory = new SSLConnectionSocketFactory(sslContext, SSLConnectionSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER);
    final Registry<ConnectionSocketFactory> reg = RegistryBuilder.<ConnectionSocketFactory>create()
                                                   .register("https", sslConnectionFactory)
                                                   .register("http", new PlainConnectionSocketFactory())
                                                   .build();
    PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager(reg);
    CloseableHttpClient httpClient = HttpClients.custom().setConnectionManager(cm).setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE).build();

            try(CloseableHttpResponse response = httpClient.execute(httpPost.build())){
                Loggers.SERVER.warn(String.format("Test Response: %s\nTest Body: %s\n",
                        response.getStatusLine().getStatusCode(),
                        IOUtils.toString(response.getEntity().getContent())));
            } catch (Exception e) { Loggers.SERVER.error("Send error", e); }
    } catch (Exception e) {
       Loggers.SERVER.error("Send error", e);
    }
    }
}
