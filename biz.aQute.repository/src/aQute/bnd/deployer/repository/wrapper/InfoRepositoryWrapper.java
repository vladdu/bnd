package aQute.bnd.deployer.repository.wrapper;

import java.io.*;
import java.util.*;
import java.util.Map.Entry;

import org.osgi.framework.*;
import org.osgi.resource.Capability;
import org.osgi.resource.Requirement;
import org.osgi.resource.Resource;
import org.osgi.service.indexer.ResourceIndexer.IndexResult;
import org.osgi.service.indexer.impl.*;
import org.osgi.service.repository.*;

import aQute.bnd.build.*;
import aQute.bnd.osgi.resource.*;
import aQute.bnd.service.repository.*;
import aQute.bnd.service.repository.SearchableRepository.ResourceDescriptor;
import aQute.bnd.version.Version;
import aQute.lib.collections.*;
import aQute.lib.filter.Filter;
import aQute.lib.hex.*;
import aQute.lib.persistentmap.*;

public class InfoRepositoryWrapper implements Repository {
	final RepoIndex								repoIndexer;
	final PersistentMap<PersistentResource>		persistent;
	final Collection< ? extends InfoRepository>	repos;				;
	long										lastTime	= 0;

	// private boolean inited;

	public InfoRepositoryWrapper(File dir, Collection< ? extends InfoRepository> repos) throws Exception {
		this.repoIndexer = new RepoIndex();
		this.repoIndexer.addAnalyzer(new KnownBundleAnalyzer(), FrameworkUtil.createFilter("(name=*)"));
		this.repos = repos;
		this.persistent = new PersistentMap<PersistentResource>(dir, PersistentResource.class);
	}

	boolean init() {
		try {
			if (System.currentTimeMillis() < lastTime + 10000)
				return true;
		}
		finally {
			lastTime = System.currentTimeMillis();
		}

		Set<String> errors = new LinkedHashSet<String>();

		try {
			//
			// Get the current repo contents
			//

			Set<String> toBeDeleted = new HashSet<String>(persistent.keySet());
			Map<String,DownloadBlocker> blockers = new HashMap<String,DownloadBlocker>();

			for (InfoRepository repo : repos) {
				Map<String,ResourceDescriptor> map = collectKeys(repo);

				for (final Map.Entry<String,ResourceDescriptor> entry : map.entrySet()) {
					final String id = entry.getKey();

					toBeDeleted.remove(id);

					if (persistent.containsKey(id))
						continue;

					final ResourceDescriptor rd = entry.getValue();

					DownloadBlocker blocker = new DownloadBlocker(null) {

						//
						// We steal the thread of the downloader to index
						//

						@Override
						public void success(File file) throws Exception {
							try {
								IndexResult index = repoIndexer.indexFile(file);

								ResourceBuilder rb = new ResourceBuilder();

								//
								// Unfortunately, we need to convert the
								// caps/reqs
								// since they are not real caps/reqs
								//

								for (org.osgi.service.indexer.Capability capability : index.capabilities) {
									CapReqBuilder cb = new CapReqBuilder(capability.getNamespace());
									cb.addAttributes(capability.getAttributes());
									cb.addDirectives(capability.getDirectives());
									rb.addCapability(cb.buildSyntheticCapability());
								}
								for (org.osgi.service.indexer.Requirement requirement : index.requirements) {
									CapReqBuilder cb = new CapReqBuilder(requirement.getNamespace());
									cb.addAttributes(requirement.getAttributes());
									cb.addDirectives(requirement.getDirectives());
									rb.addRequirement(cb.buildSyntheticRequirement());
								}

								Resource resource = rb.build();

								PersistentResource pr = new PersistentResource(resource);
								persistent.put(id, pr);
							}
							finally {
								super.success(file);
							}
						}
					};
					blockers.put(entry.getKey(), blocker);
					repo.get(rd.bsn, rd.version, null, blocker);
				}

			}

			for (Entry<String,DownloadBlocker> entry : blockers.entrySet()) {
				String key = entry.getKey();
				DownloadBlocker blocker = entry.getValue();

				String reason = blocker.getReason();
				if (reason != null) {
					errors.add(key + ": " + reason);
				}
			}
			persistent.keySet().removeAll(toBeDeleted);
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}

		if (!errors.isEmpty())
			throw new IllegalStateException("Cannot index " + repos + " due to " + errors);

		return true;
	}

	/**
	 * The repository method
	 * 
	 * @param result2
	 */

	public void findProviders(Map<Requirement,List<Capability>> result, Collection< ? extends Requirement> requirements) {
		init();

		nextReq: for (Requirement req : requirements) {
			String f = req.getDirectives().get("filter");
			if (f == null)
				continue nextReq;

			Filter filter = new Filter(f);

			for (PersistentResource presource : persistent.values()) {
				Resource resource = presource.getResource();
				List<Capability> provided = resource.getCapabilities(req.getNamespace());
				if (provided != null)
					for (Capability cap : provided) {
						if (filter.matchMap(cap.getAttributes())) {
							List<Capability> l = result.get(req);
							if (l == null)
								result.put(req, l = new ArrayList<Capability>());
							l.add(cap);
						}
					}
			}
		}
	}

	@SuppressWarnings({
			"unchecked", "rawtypes"
	})
	public Map<Requirement,Collection<Capability>> findProviders(Collection< ? extends Requirement> requirements) {
		MultiMap<Requirement,Capability> result = new MultiMap<Requirement,Capability>();
		findProviders(result, requirements);
		return (Map) result;
	}

	/*
	 * Get all the shas from the repo
	 */
	private Map<String,ResourceDescriptor> collectKeys(InfoRepository repo) throws Exception {
		Map<String,ResourceDescriptor> map = new HashMap<String,ResourceDescriptor>();

		for (String bsn : repo.list(null)) {
			for (Version version : repo.versions(bsn)) {
				ResourceDescriptor rd = repo.getDescriptor(bsn, version);
				map.put(Hex.toHexString(rd.id), rd);
			}
		}
		return map;
	}

	public String toString() {
		return "InfoRepositoryWrapper[" + repos + "]";
	}

	public void close() throws IOException {
		persistent.close();
	}

}
