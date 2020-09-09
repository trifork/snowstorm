package org.snomed.snowstorm.core.data.services;

import io.kaicode.elasticvc.api.BranchService;
import io.kaicode.elasticvc.domain.Branch;
import io.kaicode.elasticvc.domain.DomainEntity;
import io.kaicode.elasticvc.repositories.BranchRepository;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.sort.SortBuilders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.core.ElasticsearchTemplate;
import org.springframework.data.elasticsearch.core.query.NativeSearchQuery;
import org.springframework.data.elasticsearch.core.query.NativeSearchQueryBuilder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;

import static java.lang.String.format;
import static org.elasticsearch.index.query.QueryBuilders.*;

@Service
// Snowstorm branch service has some methods in addition to the ElasticVC library service.
public class SBranchService {

	@Autowired
	private ElasticsearchTemplate elasticsearchTemplate;

	@Autowired
	private BranchService branchService;

	@Autowired
	private BranchRepository branchRepository;

	@Autowired
	private DomainEntityConfiguration domainEntityConfiguration;

	private final Logger logger = LoggerFactory.getLogger(getClass());

	public Page<Branch> findAllVersionsAfterOrEqualToTimestamp(String path, Date timestamp, Pageable pageable) {
		NativeSearchQueryBuilder queryBuilder = new NativeSearchQueryBuilder()
				.withQuery(boolQuery()
						.must(QueryBuilders.termQuery("path", path))
						.must(QueryBuilders.rangeQuery("start").gte(timestamp.getTime())))
				.withSort(SortBuilders.fieldSort("start"))
				.withPageable(pageable);
		return elasticsearchTemplate.queryForPage(queryBuilder.build(), Branch.class);
	}

	public List<Branch> findByPathAndBaseTimepoint(Set<String> path, Date baseTimestamp) {
		NativeSearchQueryBuilder queryBuilder = new NativeSearchQueryBuilder()
				.withQuery(boolQuery()
						.must(termsQuery("path", path))
						.must(termQuery("base", baseTimestamp)))
				.withSort(SortBuilders.fieldSort("start"))
				.withPageable(PageRequest.of(0, path.size()));
		return elasticsearchTemplate.queryForList(queryBuilder.build(), Branch.class);
	}

	public void rollbackCommit(String branchPath, long timepoint) {
		Branch branchVersion = branchService.findAtTimepointOrThrow(branchPath, new Date(timepoint));
		if (branchVersion.getEnd() != null) {
			throw new IllegalStateException(format("Branch %s at timepoint %s is already ended, it's not the latest commit.", branchPath, timepoint));
		}
		branchService.rollbackCompletedCommit(branchVersion, new ArrayList<>(domainEntityConfiguration.getAllDomainEntityTypes()));
	}

	public void rollbackPartialCommit(String branchPath) {
		Branch latest = branchService.findBranchOrThrow(branchPath);
		if (!latest.isLocked()) {
			throw new IllegalStateException("Branch is not locked so no there is no partial commit.");
		}

		Date partialCommitTimestamp = getPartialCommitTimestamp(branchPath);
		if (partialCommitTimestamp == null) {
			throw new IllegalStateException("No partial commits found on this branch.");
		}

		// Temporarily complete partial commit to support rollback.
		// This does not complete the commit properly because we don't know what content is missing.
		// Rollback is still the best option.
		latest.setEnd(partialCommitTimestamp);
		Branch tempCompleteCommit = new Branch(branchPath);
		tempCompleteCommit.setCreation(latest.getCreation());
		tempCompleteCommit.setBase(latest.getBase());
		tempCompleteCommit.setStart(partialCommitTimestamp);
		tempCompleteCommit.setHead(partialCommitTimestamp);
		tempCompleteCommit.setMetadata(latest.getMetadata());
		tempCompleteCommit.setContainsContent(true);
		branchRepository.save(tempCompleteCommit);
		branchRepository.save(latest);

		logger.info("Found partial commit on {} at {}. Closing the branch at {} and then rolling back.", branchPath, partialCommitTimestamp.getTime(), partialCommitTimestamp.getTime());

		rollbackCommit(branchPath, partialCommitTimestamp.getTime());

		latest = branchService.findBranchOrThrow(branchPath);
		if (latest.isLocked()) {
			branchService.unlock(branchPath);
		}
	}

	public Date getPartialCommitTimestamp(String branchPath) {
		Branch latestCompleteCommit = branchService.findLatest(branchPath);
		for (Class<? extends DomainEntity> entityType : domainEntityConfiguration.getAllDomainEntityTypes()) {
			NativeSearchQuery query = new NativeSearchQueryBuilder().withQuery(
					boolQuery()
							.must(termQuery("path", branchPath))
							.must(rangeQuery("start").gt(latestCompleteCommit.getStart().getTime())))
					.build();
			List<? extends DomainEntity> domainEntities = elasticsearchTemplate.queryForList(query, entityType);
			if (!domainEntities.isEmpty()) {
				return domainEntities.get(0).getStart();
			}
		}
		return null;
	}
}
