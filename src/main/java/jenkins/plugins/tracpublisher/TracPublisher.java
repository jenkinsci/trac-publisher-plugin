package jenkins.plugins.tracpublisher;

import hudson.Extension;
import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;

import java.io.IOException;

import net.sf.json.JSONObject;

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

	public String rpcAddress;
	public String username;
	public String password;
	public boolean useDetailedComments;

	@DataBoundConstructor
	public TracPublisher(String rpcAddress, String username, String password,
			boolean useDetailedComments) {
		this.rpcAddress = rpcAddress;
		this.username = username;
		this.password = password;
		this.useDetailedComments = useDetailedComments;
	}

	public BuildStepMonitor getRequiredMonitorService() {
		return BuildStepMonitor.NONE;
	}

	@Override
	public boolean needsToRunAfterFinalized() {
		return true;
	}

	@Override
	public boolean perform(AbstractBuild<?, ?> build, Launcher launcher,
			BuildListener listener) throws InterruptedException, IOException {
		new TracIssueUpdater(build, listener, rpcAddress, username, password,
				useDetailedComments).updateIssues();
		return true;
	}

	@Extension
	public static final class DescriptorImpl extends
			BuildStepDescriptor<Publisher> {

		public DescriptorImpl() {
			load();
		}

		private String rpcAddress;
		private String username;
		private String password;
		private boolean useDetailedComments;

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

			rpcAddress = json.getString("rpcAddress");
			username = json.getString("username");
			password = json.getString("password");
			useDetailedComments = json.getBoolean("useDetailedComments");

			save();

			return super.configure(req, json);
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

		public boolean isUseDetailedComments() {
			return useDetailedComments;
		}

	}
}
