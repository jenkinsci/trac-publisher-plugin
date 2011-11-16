package jenkins.plugins.tracpublisher;

import hudson.Extension;
import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.scm.ChangeLogSet;
import hudson.scm.ChangeLogSet.Entry;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.sf.json.JSONObject;

import org.apache.xmlrpc.XmlRpcException;
import org.apache.xmlrpc.client.XmlRpcClient;
import org.apache.xmlrpc.client.XmlRpcClientConfigImpl;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

/**
 * A notifier that attaches comments to Trac tickets upon successful build. The
 * notifier searches the changesets for references to ticket numbers and adds a
 * link to the jenkins build to those issues. It enables people to easily find
 * builds containing changes related to issues.
 * 
 * @author batkinson
 */
public class TracPublisher extends Notifier {

	private String buildServerAddress;
	private String rpcAddress;
	private String username;
	private String password;

	public TracPublisher() {
		super();
	}

	@DataBoundConstructor
	public TracPublisher(String buildServerAddress, String rpcAddress,
			String username, String password) {
		super();
		this.buildServerAddress = buildServerAddress;
		this.rpcAddress = rpcAddress;
		this.username = username;
		this.password = password;
	}

	public BuildStepMonitor getRequiredMonitorService() {
		return BuildStepMonitor.NONE;
	}

	@Override
	public boolean needsToRunAfterFinalized() {
		return true;
	}

	Pattern issuePattern = Pattern.compile("[#](\\d+)");

	@Override
	public boolean perform(AbstractBuild<?, ?> build, Launcher launcher,
			BuildListener listener) throws InterruptedException, IOException {

		Result result = build.getResult();
		if (Result.SUCCESS.equals(result)) {

			// Build set of affected issues for this build using commit msgs
			Set<Integer> affectedIssues = new HashSet<Integer>();
			ChangeLogSet<? extends Entry> changes = build.getChangeSet();
			for (Entry change : changes) {
				String message = change.getMsg();
				Matcher matcher = issuePattern.matcher(message);
				while (matcher.find()) {
					String issueString = matcher.group(1);
					Integer issue = Integer.parseInt(issueString);
					affectedIssues.add(issue);
				}
			}

			if (affectedIssues.size() > 0)
				listener.getLogger().format(
						"Updating %d Trac issue(s): server=%s, user=%s\n",
						affectedIssues.size(), rpcAddress, username);

			// Run through the issues and update them with the build
			for (Integer issue : affectedIssues) {
				try {
					String buildDN = build.getFullDisplayName();
					listener.getLogger().format("Updating issue %d with %s\n:",
							issue, buildDN);
					updateIssue(issue, buildDN, build.getUrl());
				} catch (XmlRpcException e) {
					e.printStackTrace();
				}
			}
		}

		return true;
	}

	@SuppressWarnings("rawtypes")
	private void updateIssue(Integer issueNumber, String buildName, String url)
			throws MalformedURLException, XmlRpcException {
		XmlRpcClient client = new XmlRpcClient();
		XmlRpcClientConfigImpl config = new XmlRpcClientConfigImpl();
		config.setBasicUserName(username);
		config.setBasicPassword(password);
		config.setServerURL(new URL(rpcAddress));
		client.setConfig(config);
		String message = String.format("Referenced in build [%s/%s %s]",
				buildServerAddress, url, buildName);
		Object[] params = new Object[] { issueNumber, message, new HashMap(),
				Boolean.FALSE };
		client.execute("ticket.update", params);
	}

	@Extension
	public static final class DescriptorImpl extends
			BuildStepDescriptor<Publisher> {

		public DescriptorImpl() {
			load();
		}

		private String buildServerAddress;
		private String rpcAddress;
		private String username;
		private String password;

		@Override
		public boolean isApplicable(Class<? extends AbstractProject> jobType) {
			return true;
		}

		@Override
		public String getDisplayName() {
			return "Add link to Trac issues";
		}

		@Override
		public boolean configure(StaplerRequest req, JSONObject json)
				throws hudson.model.Descriptor.FormException {

			buildServerAddress = json.getString("buildServerAddress");
			rpcAddress = json.getString("rpcAddress");
			username = json.getString("username");
			password = json.getString("password");

			save();

			return super.configure(req, json);
		}

		public String getBuildServerAddress() {
			return buildServerAddress;
		}

		public String getRpcAddress() {
			return rpcAddress;
		}

		public String getUsername() {
			return username;
		}

		public String getPassword() {
			return password;
		}
	}
}
