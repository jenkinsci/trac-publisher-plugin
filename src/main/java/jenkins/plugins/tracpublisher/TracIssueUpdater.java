package jenkins.plugins.tracpublisher;

import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.model.AbstractBuild;
import hudson.scm.ChangeLogSet;
import hudson.scm.ChangeLogSet.Entry;

import java.io.PrintStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jenkins.model.Jenkins;

import org.apache.xmlrpc.XmlRpcException;
import org.apache.xmlrpc.client.XmlRpcClient;
import org.apache.xmlrpc.client.XmlRpcClientConfigImpl;
import org.apache.xmlrpc.client.XmlRpcCommonsTransportFactory;

/**
 * Encapsulates the updating of Trac issues from Jenkins. An instance is meant
 * to service only a single publish. Subsequent requests should create a new
 * instance per operation.
 * 
 * @author batkinson
 * 
 */
public class TracIssueUpdater {

	private static Logger log = Logger.getLogger(TracIssueUpdater.class.getName());

	Pattern issuePattern = Pattern.compile("[#](\\d+)");

	AbstractBuild<?, ?> build;
	BuildListener listener;
	String buildServerAddress;
	String rpcAddress;
	String username;
	String password;
	boolean useDetailedComments;
	PrintStream buildLog;

	HashMap<Integer, StringBuilder> priorIssueRefs;
	HashMap<Integer, StringBuilder> issueRefs;

	public TracIssueUpdater(AbstractBuild<?, ?> build, BuildListener listener,
			String rpcAddress, String username, String password,
			boolean useDetailedComments) {
		this.build = build;
		this.listener = listener;
		this.buildServerAddress = Jenkins.getInstance().getRootUrl();
		this.rpcAddress = rpcAddress;
		this.username = username;
		this.password = password;
		this.useDetailedComments = useDetailedComments;
		this.buildLog = listener.getLogger();
		priorIssueRefs = new HashMap<Integer, StringBuilder>();
		issueRefs = new HashMap<Integer, StringBuilder>();
	}

	/**
	 * Updates the issues based on the state of the updater. Calling this method
	 * more than once has undefined behavior.
	 * 
	 * @throws MalformedURLException
	 */
	public void updateIssues() throws MalformedURLException {

		Result result = build.getResult();

		if (Result.SUCCESS.equals(result)) {

			// Scan for failed builds prior to this one, and include them.
			AbstractBuild<?, ?> priorBuild = build.getPreviousBuild();
			while (priorBuild != null
					&& !Result.SUCCESS.equals(priorBuild.getResult())) {
				HashMap<Integer, StringBuilder> priorRefs = getIssueRefs(priorBuild);
				for (Map.Entry<Integer, StringBuilder> entry : priorRefs
						.entrySet()) {
					if (!priorIssueRefs.containsKey(entry.getKey()))
						priorIssueRefs.put(entry.getKey(), entry.getValue());
					else
						priorIssueRefs.get(entry.getKey()).append('\n')
								.append(entry.getValue());
				}
				priorBuild = priorBuild.getPreviousBuild();
			}

			Set<Integer> correctedIssues = new HashSet<Integer>(
					priorIssueRefs.keySet());

			issueRefs = getIssueRefs(build);
			Set<Integer> successfulIssues = new HashSet<Integer>(
					issueRefs.keySet());

			successfulIssues.addAll(issueRefs.keySet());

			// Only update once, direct ref supercedes prior ref
			correctedIssues.removeAll(successfulIssues);

			if (correctedIssues.size() + successfulIssues.size() > 0) {
				buildLog.format(
						"Updating %d Trac issue(s): server=%s, user=%s\n",
						successfulIssues.size(), rpcAddress, username);
				if (this.buildServerAddress == null) {
					buildLog.println("Jenkins URL was null, please configure to enable issue updating.");
					return;
				}
				if (this.rpcAddress == null) {
					buildLog.println("Trac XMLRPC URL was null, please configure your build to enable issue updating.");
					return;
				}
			}

			for (Integer issue : successfulIssues)
				updateSuccessfulIssue(issue);

			for (Integer issue : correctedIssues)
				updateCorrectedIssue(issue);
		}
	}

	/**
	 * Updates the specified issue with a reference to this successful build.
	 * Called if the issue was mentioned in SCM comments of one of the failing
	 * builds after the prior successful build.
	 * 
	 * @param issue
	 * @throws MalformedURLException
	 */
	private void updateCorrectedIssue(Integer issue)
			throws MalformedURLException {
		try {
			buildLog.format("Updating corrected issue %d with %s\n:", issue,
					build.getDisplayName());
			updateIssue(issue, true);
		} catch (XmlRpcException e) {
			log.log(Level.SEVERE, "failed to update corrected issue", e);
		}
	}

	/**
	 * Updates the specified issue with a reference to this successful build.
	 * Called if the issue was mentioned in this build's SCM comments.
	 * 
	 * @param issue
	 * @throws MalformedURLException
	 */
	private void updateSuccessfulIssue(Integer issue)
			throws MalformedURLException {
		try {
			buildLog.format("Updating successful issue %d with %s\n:", issue,
					build.getDisplayName());
			updateIssue(issue, false);
		} catch (XmlRpcException e) {
			log.log(Level.SEVERE, "failed to update successful issue", e);
		}
	}

	/**
	 * Returns a map of issue ids to scm messages that reference them in the
	 * given build.
	 * 
	 * @param build
	 * @return a set of issues referenced or an empty list (never null)
	 */
	private HashMap<Integer, StringBuilder> getIssueRefs(
			AbstractBuild<?, ?> build) {
		HashMap<Integer, StringBuilder> referencedIssues = new HashMap<Integer, StringBuilder>();
		ChangeLogSet<? extends Entry> changes = build.getChangeSet();
		for (Entry change : changes) {
			String message = change.getMsg();
			Matcher matcher = issuePattern.matcher(message);
			while (matcher.find()) {
				String issueString = matcher.group(1);
				Integer issue = Integer.parseInt(issueString);
				if (!referencedIssues.containsKey(issue))
					referencedIssues.put(issue, new StringBuilder(message));
				else
					referencedIssues.get(issue).append('\n').append(message);
			}
		}
		return referencedIssues;
	}

	/**
	 * Performs the actual issue update using XMLRPC to a Trac instance with a
	 * configured XMLRPC plugin and ticket updates enabled.
	 * 
	 * @param issueNumber
	 * @param buildName
	 * @param url
	 * @throws MalformedURLException
	 * @throws XmlRpcException
	 */
	@SuppressWarnings("rawtypes")
	private void updateIssue(Integer issueNumber, boolean isCorrected)
			throws MalformedURLException, XmlRpcException {
		XmlRpcClient client = new XmlRpcClient();
		client.setTransportFactory(new XmlRpcCommonsTransportFactory(client));
		XmlRpcClientConfigImpl config = new XmlRpcClientConfigImpl();
		config.setBasicUserName(username);
		config.setBasicPassword(password);
		config.setServerURL(new URL(rpcAddress));
		client.setConfig(config);

		String message = createMessage(issueNumber, isCorrected);

		Object[] params = new Object[] { issueNumber, message, new HashMap(),
				Boolean.FALSE };
		client.execute("ticket.update", params);
	}

	/**
	 * Generates a message based on the specified message and the internal state
	 * of the updater.
	 * 
	 * @param issueNumber
	 *            the issue to create the message for
	 * @param isCorrected
	 *            whether the message is for a 'corrected' issue
	 * @return
	 */
	private String createMessage(Integer issueNumber, boolean isCorrected) {

		String message = "";
		String buildDN = build.getFullDisplayName();
		String buildUrl = build.getUrl();
		String issueMessage = isCorrected ? "Referenced in unsuccessful builds prior to"
				: "Referenced in build";

		if (useDetailedComments) {
			String scmMessages = (isCorrected ? priorIssueRefs.get(issueNumber)
					: issueRefs.get(issueNumber)).toString();
			message = String.format("%s [%s/%s %s]:\n%s", issueMessage,
					buildServerAddress, buildUrl, buildDN, scmMessages);
		} else {
			message = String.format("%s [%s/%s %s]", issueMessage,
					buildServerAddress, buildUrl, buildDN);
		}

		return message;
	}
}
