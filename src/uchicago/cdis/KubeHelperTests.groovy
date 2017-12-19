package uchicago.cdis;

import java.util.logging.*;

class  KubeHelperTests extends GroovyTestCase {
  private static final Logger log = Logger.getLogger(KubeHelperTests.class.getName());
    
  /**
   * Mock for injecting into KubeHelper - just implements sh()
   * method that remembers its arguments ...
   */
  static class JenkinsStepsMock {
    private List<Map> shHistory = new ArrayList<Map>();
    /**
     * Circular queue of responses - respond with "" if queue is empty
     * @property shResponseQueue
     */
    private List<String> shResponseQueue = new ArrayList<String>();

    /**
     * shResponseQueue as empty list
     */
    JenkinsStepsMock() {
      this(Collections.emptyList());
    }
    
    JenkinsStepsMock(List<String> shResponseQueue) {
      this.shResponseQueue = new ArrayList(shResponseQueue);
    }

    String sh(Map args) {
      String response = "";
      if (!shResponseQueue.isEmpty()) {
        response = shResponseQueue.get(shHistory.size() % shResponseQueue.size());
      }
      // update history after computing response ...
      shHistory.add(args);
      return response;
    }
  }
  
  void testDeployBranch() {
    JenkinsStepsMock mock = new JenkinsStepsMock();
    KubeHelper helper = new KubeHelper(mock);
    helper.deployBranch("service", "branch", "sha");
    assert mock.shHistory.size() == 7;
    mock.shHistory.stream().forEach(
      {
        arg -> 
          log.log(Level.INFO, "---" + arg.get("script"));
          //System.out.println("---" + arg.get("script"));
      }
    );
  }

  void testDeployExistingBranch() {
    JenkinsStepsMock mock = new JenkinsStepsMock(Collections.singletonList("branch"));
    KubeHelper helper = new KubeHelper(mock);
    helper.deployBranch("service", "branch", "sha");
    assert mock.shHistory.size() == 6;
    mock.shHistory.stream().forEach(
      {
        arg -> 
          log.log(Level.INFO, "---" + arg.get("script"));
          //System.out.println("---" + arg.get("script"));
      }
    );
  }
}