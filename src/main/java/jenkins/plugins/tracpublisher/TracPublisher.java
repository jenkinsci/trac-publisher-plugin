package jenkins.plugins.tracpublisher;

import hudson.Extension;
import hudson.Launcher;
import hudson.Util;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Hudson;
import hudson.scm.ChangeLogSet;
import hudson.scm.ChangeLogSet.AffectedFile;
import hudson.scm.ChangeLogSet.Entry;
import hudson.scm.RepositoryBrowser;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.sf.json.JSONObject;

import org.apache.commons.lang.StringUtils;
import org.apache.xmlrpc.XmlRpcException;
import org.apache.xmlrpc.client.XmlRpcClient;
import org.apache.xmlrpc.client.XmlRpcClientConfigImpl;
import org.apache.xmlrpc.client.XmlRpcCommonsTransportFactory;
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

    private static final Logger LOGGER = Logger.getLogger(TracPublisher.class
	    .getName());

    public String buildServerAddress;
    public String rpcAddress;
    public String username;
    public String password;

    @DataBoundConstructor
    public TracPublisher(String buildServerAddress, String rpcAddress,
	    String username, String password) {
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
	List<String> commitMessages = new ArrayList<String>();
	ChangeLogSet<? extends Entry> changeSets = build.getChangeSet();
	for (Entry changeSet : changeSets) {
	    commitMessages.add(changeSet.getMsg());
	}

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
		LOGGER.info(String.format(
			"Updating %d Trac issue(s): server=%s, user=%s\n",
			successfulIssues.size(), rpcAddress, username));

	    for (Integer issue : successfulIssues)
		updateSuccessfulIssue(build, issue);

	    for (Integer issue : correctedIssues)
		updateCorrectedIssue(build, issue);
	}

	return true;
    }

    private static String createComment(AbstractBuild<?, ?> build,
	    boolean wikiStyle, String scmComments, boolean recordScmChanges,
	    String issueId) {
	String jenkinsRootUrl = Hudson.getInstance().getRootUrl();
	String comment = String
		.format(wikiStyle ? " [[Image(%1$simages/16x16/%3$s)]] [%4$s %2$s]\n     %5$s\n     Result = %6$s"
			: " %2$s (See [%4$s])\n    %5$s\n     Result = %6$s",
			jenkinsRootUrl, build, build.getResult().color
				.getImage(), Util.encode(jenkinsRootUrl
				+ build.getUrl()), scmComments, build
				.getResult().toString());
	if (recordScmChanges) {
	    List<String> scmChanges = getScmComments(wikiStyle, build, issueId);
	    StringBuilder sb = new StringBuilder(comment);
	    for (String scmChange : scmChanges) {
		sb.append("\n").append(scmChange);
	    }
	    return sb.toString();
	}
	return comment;
    }

    private static List<String> getScmComments(boolean wikiStyle,
	    AbstractBuild<?, ?> build, String issueId) {
	RepositoryBrowser repoBrowser = null;
	if (build.getProject().getScm() != null) {
	    repoBrowser = build.getProject().getScm().getEffectiveBrowser();
	}
	List<String> scmChanges = new ArrayList<String>();
	for (Entry change : build.getChangeSet()) {
	    if (issueId != null
		    && !StringUtils.contains(change.getMsg(), issueId)) {
		continue;
	    }
	    try {
		String uid = change.getAuthor().getId();
		URL url = repoBrowser == null ? null : repoBrowser
			.getChangeSetLink(change);
		StringBuilder scmChange = new StringBuilder();
		if (StringUtils.isNotBlank(uid)) {
		    scmChange.append(uid).append(" : ");
		}
		if (url != null && StringUtils.isNotBlank(url.toExternalForm())) {
		    if (wikiStyle) {
			String revision = getRevision(change);
			if (revision != null) {
			    scmChange.append("[").append(url.toExternalForm());
			    scmChange.append(" Revision ");
			    scmChange.append(revision).append("]");
			} else {
			    scmChange.append("[").append(url.toExternalForm())
				    .append("]");
			}
		    } else {
			scmChange.append(url.toExternalForm());
		    }
		}
		scmChange.append("\n\nFiles : ").append("\n");
		// see http://issues.jenkins-ci.org/browse/JENKINS-2508
		// added additional try .. catch; getAffectedFiles is not
		// supported by all SCM implementations
		try {
		    for (AffectedFile affectedFile : change.getAffectedFiles()) {
			scmChange.append("* ").append(affectedFile.getPath())
				.append("\n");
		    }
		} catch (UnsupportedOperationException e) {
		    LOGGER.warning("Unsupported SCM operation 'getAffectedFiles'. Fall back to getAffectedPaths.");
		    for (String affectedPath : change.getAffectedPaths()) {
			scmChange.append("* ").append(affectedPath)
				.append("\n");
		    }
		}
		if (scmChange.length() > 0) {
		    scmChanges.add(scmChange.toString());
		}
	    } catch (IOException e) {
		LOGGER.warning("skip failed to calculate scm repo browser link "
			+ e.getMessage());
	    }
	}
	return scmChanges;
    }

    private static String getRevision(Entry entry) {
	String commitId = entry.getCommitId();
	if (commitId != null) {
	    return commitId;
	}

	// fall back to old SVN-specific solution, if we have only installed an
	// old subversion-plugin which doesn't implement getCommitId, yet
	try {
	    Class<?> clazz = entry.getClass();
	    Method method = clazz.getMethod("getRevision", (Class[]) null);
	    if (method == null) {
		return null;
	    }
	    Object revObj = method.invoke(entry, (Object[]) null);
	    return (revObj != null) ? revObj.toString() : null;
	} catch (Exception e) {
	    return null;
	}
    }

    private void updateCorrectedIssue(AbstractBuild<?, ?> build, Integer issue)
	    throws MalformedURLException {
	try {
	    String buildDN = build.getFullDisplayName();
	    String buildUrl = build.getUrl();
	    LOGGER.info(String.format("Updating corrected issue %d with %s\n:",
		    issue, buildDN));

	    StringBuilder aggregateComment = createAggregateComment(build,
		    issue);
	    String comment = createComment(build, true,
		    aggregateComment.toString(), true, issue.toString());

	    updateIssue("Referenced in unsuccessful builds prior to" + comment,
		    issue, buildDN, buildUrl);
	} catch (XmlRpcException e) {
	    e.printStackTrace();
	}
    }

    private void updateSuccessfulIssue(AbstractBuild<?, ?> build, Integer issue)
	    throws MalformedURLException {
	try {
	    String buildDN = build.getFullDisplayName();
	    String buildUrl = build.getUrl();
	    LOGGER.info(String.format(
		    "Updating successful issue %d with %s\n:", issue, buildDN));

	    StringBuilder aggregateComment = createAggregateComment(build,
		    issue);
	    String comment = createComment(build, true,
		    aggregateComment.toString(), true, issue.toString());

	    updateIssue("Referenced in build" + comment, issue, buildDN,
		    buildUrl);
	} catch (XmlRpcException e) {
	    e.printStackTrace();
	}
    }

    private StringBuilder createAggregateComment(AbstractBuild<?, ?> build,
	    Integer issue) {
	StringBuilder aggregateComment = new StringBuilder();
	for (Entry e : build.getChangeSet()) {
	    if (e.getMsg().toUpperCase().contains(issue.toString())) {
		aggregateComment.append(e.getMsg());

		String revision = getRevision(e);
		if (revision != null) {
		    aggregateComment.append(" (Revision ").append(revision)
			    .append(")");
		}
		aggregateComment.append("\n");
	    }
	}
	return aggregateComment;
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
	Object[] params = new Object[] { issueNumber, ticketMessage,
		new HashMap(), Boolean.FALSE };
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
