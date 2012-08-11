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
import java.util.Set;
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

	Pattern issuePattern = Pattern.compile("[#](\\d+)");

	AbstractBuild<?, ?> build;
	BuildListener listener;
	String buildServerAddress;
	String rpcAddress;
	String username;
	String password;
	PrintStream log;

	public TracIssueUpdater(AbstractBuild<?, ?> build, BuildListener listener,
			String rpcAddress, String username, String password) {
		this.build = build;
		this.listener = listener;
		this.buildServerAddress = Jenkins.getInstance().getRootUrl();
		this.rpcAddress = rpcAddress;
		this.username = username;
		this.password = password;
		this.log = listener.getLogger();
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

			Set<Integer> correctedIssues = new HashSet<Integer>();
			Set<Integer> successfulIssues = new HashSet<Integer>();

			// Scan for failed builds prior to this one, and include them.
			AbstractBuild<?, ?> priorBuild = build.getPreviousBuild();
			while (priorBuild != null
					&& !Result.SUCCESS.equals(priorBuild.getResult())) {
				correctedIssues.addAll(getIssueRefs(priorBuild));
				priorBuild = priorBuild.getPreviousBuild();
			}

			successfulIssues.addAll(getIssueRefs(build));

			// Only update once, direct ref supercedes prior ref
			correctedIssues.removeAll(successfulIssues);

			if (correctedIssues.size() + successfulIssues.size() > 0)
				log.format("Updating %d Trac issue(s): server=%s, user=%s\n",
						successfulIssues.size(), rpcAddress, username);

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
			String buildDN = build.getFullDisplayName();
			String buildUrl = build.getUrl();
			log.format("Updating corrected issue %d with %s\n:", issue, buildDN);
			updateIssue("Referenced in unsuccessful builds prior to", issue,
					buildDN, buildUrl);
		} catch (XmlRpcException e) {
			e.printStackTrace();
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
			String buildDN = build.getFullDisplayName();
			String buildUrl = build.getUrl();
			log.format("Updating successful issue %d with %s\n:", issue,
					buildDN);
			updateIssue("Referenced in build", issue, buildDN, buildUrl);
		} catch (XmlRpcException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Returns a list of issue ids referenced in the given build's changeset
	 * messages.
	 * 
	 * @param build
	 * @return a set of issues referenced or an empty list (never null)
	 */
	private Set<Integer> getIssueRefs(AbstractBuild<?, ?> build) {
		Set<Integer> referencedIssues = new HashSet<Integer>();
		ChangeLogSet<? extends Entry> changes = build.getChangeSet();
		for (Entry change : changes) {
			String message = change.getMsg();
			Matcher matcher = issuePattern.matcher(message);
			while (matcher.find()) {
				String issueString = matcher.group(1);
				Integer issue = Integer.parseInt(issueString);
				referencedIssues.add(issue);
			}
		}
		return referencedIssues;
	}

	/**
	 * Performs the actual issue update using XMLRPC to a Trac instance with a
	 * configured XMLRPC plugin and ticket updates enabled.
	 * 
	 * @param ticketMessage
	 * @param issueNumber
	 * @param buildName
	 * @param url
	 * @throws MalformedURLException
	 * @throws XmlRpcException
	 */
	@SuppressWarnings("rawtypes")
	private void updateIssue(String ticketMessage, Integer issueNumber,
			String buildName, String url) throws MalformedURLException,
			XmlRpcException {
		XmlRpcClient client = new XmlRpcClient();
		client.setTransportFactory(new XmlRpcCommonsTransportFactory(client));
		XmlRpcClientConfigImpl config = new XmlRpcClientConfigImpl();
		config.setBasicUserName(username);
		config.setBasicPassword(password);
		config.setServerURL(new URL(rpcAddress));
		client.setConfig(config);
		String message = String.format("%s [%s/%s %s]", ticketMessage,
				buildServerAddress, url, buildName);
		Object[] params = new Object[] { issueNumber, message, new HashMap(),
				Boolean.FALSE };
		client.execute("ticket.update", params);
	}
}
