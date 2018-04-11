import com.intellij.execution.*;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsListener;
import com.intellij.execution.testframework.sm.runner.SMTestProxy;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import org.apache.commons.codec.binary.Base64;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.eclipse.egit.github.core.Issue;
import org.eclipse.egit.github.core.client.GitHubClient;
import org.eclipse.egit.github.core.service.IssueService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class TextBoxes extends AnAction {
    @Override
    public void update(AnActionEvent e) {
        super.update(e);
        VirtualFile v_file = e.getData(CommonDataKeys.VIRTUAL_FILE);
        if (v_file.getExtension().equals("json")) {
            e.getPresentation().setEnabledAndVisible(true);
        } else {
            e.getPresentation().setEnabledAndVisible(false);
        }
    }

    @Override
    public void actionPerformed(AnActionEvent anActionEvent) {
        Project project = anActionEvent.getProject();
        VirtualFile v_file = anActionEvent.getData(CommonDataKeys.VIRTUAL_FILE);
        System.out.println(v_file.getPath());
        JSONParser parser = new JSONParser();
        ArrayList<HashMap<String, String>> req_array;
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(v_file.getPath()), "UTF8"));
            Object obj = parser.parse(reader);
            JSONObject jsonObject = (JSONObject) obj;
            String target = (String) jsonObject.get("target");
            JSONArray reqArray = (JSONArray) jsonObject.get("requirement");
            req_array = new ArrayList<>();
            for (Object reqObject : reqArray) {
                HashMap<String, String> hashMap = new HashMap<>();
                JSONObject reqObj = (JSONObject) reqObject;
                String req_ref = (String) reqObj.get("requirement_ref");
                String cate = (String) reqObj.get("category");
                String sub_cate = (String) reqObj.get("sub_category");
                String method = (String) reqObj.get("method");
                hashMap.put("req_ref", req_ref);
                hashMap.put("cate", cate);
                hashMap.put("sub_cate", sub_cate);
                hashMap.put("method", method);
                req_array.add(hashMap);
            }

            RunManager runManager = RunManagerEx.getInstance(project);
            RunnerAndConfigurationSettings runnerAndConfigurationSettings =
                    runManager.getSelectedConfiguration();
            RunConfiguration someConfig = null;
            for (RunConfiguration runConfiguration : runManager.getAllConfigurationsList())
                if (runConfiguration.getName().equals(target))
                    someConfig = runConfiguration;
            if (someConfig == null) {
                Messages.showMessageDialog(project, "Not Exist Test Configuration - '" + target + "'", "Intelli-Feature", Messages.getErrorIcon());
            }

            project.getMessageBus().connect().subscribe(SMTRunnerEventsListener.TEST_STATUS, new SMTRunnerEventsListener() {
                ArrayList<HashMap<String, String>> hashMaps;
                HashMap<String, String> hashMap;

                @Override
                public void onTestingStarted(@NotNull SMTestProxy.SMRootTestProxy smRootTestProxy) {
                    hashMaps = new ArrayList<>();
                }

                @Override
                public void onTestingFinished(@NotNull SMTestProxy.SMRootTestProxy smRootTestProxy) {
                    int cnt_success = 0;
                    int cnt_fail = 0;
                    for (HashMap<String, String> result_hashmap : hashMaps) {
                        for (int i = 0; i < req_array.size(); i++) {
                            HashMap<String, String> tmp_hashmap = req_array.get(i);
                            if (tmp_hashmap.get("method").equals(result_hashmap.get("name"))) {
                                tmp_hashmap.put("result", result_hashmap.get("result"));
                                if (result_hashmap.get("result").equals("success")) {
                                    cnt_success++;
                                } else if (result_hashmap.get("result").equals("fail")) {
                                    cnt_fail++;
                                }
                            }
                            req_array.set(i, tmp_hashmap);
                        }
                    }
                    String user = "githubid";
                    String pwd = "githubpw";


                    GitHubClient gitHubClient = new GitHubClient();
                    gitHubClient.setCredentials(user,pwd);
                    IssueService issueService = new IssueService(gitHubClient);
                    try {
                        Issue issue = new Issue();
                        issue.setTitle("Junit Test Report");
                        String result_str = "";
                        for(HashMap<String,String> resultHashmap : req_array){
                            String req_ref = resultHashmap.get("req_ref");
                            String cate = resultHashmap.get("cate");
                            String sub_cate = resultHashmap.get("sub_cate");
                            String method = resultHashmap.get("method");
                            String result = resultHashmap.get("result");
                            result_str += "- ["+req_ref+"] "+cate+"-"+sub_cate+" "+method+" ["+result+"]\r\n";
                        }
                        result_str += "Success : " + cnt_success + "\r\nFail : " + cnt_fail + "\r\nTotal Count : " + req_array.size();
                        issue.setBody(result_str);
                        issueService.createIssue(user,"jenkins-test",issue);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    Messages.showMessageDialog(project, "Success : " + cnt_success + "\r\nFail : " + cnt_fail + "\r\nTotal Count : " + req_array.size(), "Test Result", Messages.getInformationIcon());
                }

                @Override
                public void onTestsCountInSuite(int i) {

                }

                @Override
                public void onTestStarted(@NotNull SMTestProxy smTestProxy) {
                    hashMap = new HashMap<>();
                    hashMap.put("name", smTestProxy.getName());
                    hashMap.put("result", "success");
                }

                @Override
                public void onTestFinished(@NotNull SMTestProxy smTestProxy) {
                    hashMaps.add(hashMap);
                }

                @Override
                public void onTestFailed(@NotNull SMTestProxy smTestProxy) {
                    hashMap.put("result", "fail");
                }

                @Override
                public void onTestIgnored(@NotNull SMTestProxy smTestProxy) {

                }

                @Override
                public void onSuiteFinished(@NotNull SMTestProxy smTestProxy) {

                }

                @Override
                public void onSuiteStarted(@NotNull SMTestProxy smTestProxy) {

                }

                @Override
                public void onCustomProgressTestsCategory(@Nullable String s, int i) {

                }

                @Override
                public void onCustomProgressTestStarted() {

                }

                @Override
                public void onCustomProgressTestFailed() {

                }

                @Override
                public void onCustomProgressTestFinished() {

                }

                @Override
                public void onSuiteTreeNodeAdded(SMTestProxy smTestProxy) {

                }

                @Override
                public void onSuiteTreeStarted(SMTestProxy smTestProxy) {

                }
            });
            Executor executor = DefaultRunExecutor.getRunExecutorInstance();
            ProgramRunnerUtil.executeConfiguration(project, runnerAndConfigurationSettings, executor);
        } catch (
                Exception e)

        {
            e.printStackTrace();
        }
    }
}

