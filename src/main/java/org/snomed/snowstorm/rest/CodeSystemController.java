package org.snomed.snowstorm.rest;

import io.kaicode.rest.util.branchpathrewrite.BranchPathUriUtil;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import org.elasticsearch.common.Strings;
import org.snomed.snowstorm.core.data.domain.CodeSystem;
import org.snomed.snowstorm.core.data.domain.CodeSystemVersion;
import org.snomed.snowstorm.core.data.domain.fieldpermissions.CodeSystemCreate;
import org.snomed.snowstorm.core.data.services.CodeSystemService;
import org.snomed.snowstorm.core.data.services.CodeSystemUpgradeService;
import org.snomed.snowstorm.core.data.services.NotFoundException;
import org.snomed.snowstorm.core.data.services.ServiceException;
import org.snomed.snowstorm.dailybuild.DailyBuildService;
import org.snomed.snowstorm.extension.ExtensionAdditionalLanguageRefsetUpgradeService;
import org.snomed.snowstorm.rest.pojo.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;

import static java.lang.Boolean.TRUE;

@RestController
@Api(tags = "Code Systems", description = "-")
@RequestMapping(value = "/codesystems", produces = "application/json")
public class CodeSystemController {

	@Autowired
	private CodeSystemService codeSystemService;

	@Autowired
	private CodeSystemUpgradeService codeSystemUpgradeService;

	@Autowired
	private DailyBuildService dailyBuildService;

	@Autowired
	private ExtensionAdditionalLanguageRefsetUpgradeService extensionAdditionalLanguageRefsetUpgradeService;

	@ApiOperation(value = "Create a code system",
			notes = "Required fields are shortName and branch.\n" +
					"shortName should use format SNOMEDCT-XX where XX is the country code for national extensions.\n" +
					"dependantVersion uses effectiveTime format and can be used if the new code system depends on an older version of the parent code system, " +
					"otherwise the latest version will be selected automatically.\n" +
					"defaultLanguageCode can be used to force the sort order of the languages listed under the codesystem, " +
					"otherwise these are sorted by the number of active translated terms.\n" +
					"maintainerType has no effect on API behaviour but can be used in frontend applications for extension categorisation.\n" +
					"defaultLanguageReferenceSet has no effect API behaviour but can be used by browsers to reflect extension preferences. ")
	@RequestMapping(method = RequestMethod.POST)
	@PreAuthorize("hasPermission('ADMIN', #codeSystem.branchPath)")
	public ResponseEntity<Void> createCodeSystem(@RequestBody CodeSystemCreate codeSystem) {
		codeSystemService.createCodeSystem((CodeSystem) codeSystem);
		return ControllerHelper.getCreatedResponse(codeSystem.getShortName());
	}

	@ApiOperation(value = "List code systems",
			notes = "List all code systems.\n" +
			"forBranch is an optional parameter to find the code system which the specified branch is within.")
	@RequestMapping(method = RequestMethod.GET)
	public ItemsPage<CodeSystem> listCodeSystems(@RequestParam(required = false) String forBranch) {
		if (!Strings.isNullOrEmpty(forBranch)) {
			CodeSystem codeSystem = codeSystemService.findClosestCodeSystemUsingAnyBranch(forBranch, true);
			if (codeSystem != null) {
				return new ItemsPage<>(Collections.singleton(codeSystem));
			} else {
				return new ItemsPage<>(Collections.emptySet());
			}
		} else {
			return new ItemsPage<>(codeSystemService.findAll());
		}
	}

	@ApiOperation("Retrieve a code system")
	@RequestMapping(value = "/{shortName}", method = RequestMethod.GET)
	public CodeSystem findCodeSystem(@PathVariable String shortName) {
		return ControllerHelper.throwIfNotFound("Code System", codeSystemService.find(shortName));
	}

	@ApiOperation("Update a code system")
	@RequestMapping(value = "/{shortName}", method = RequestMethod.PUT)
	public CodeSystem updateCodeSystem(@PathVariable String shortName, @RequestBody CodeSystemUpdateRequest updateRequest) {
		CodeSystem codeSystem = findCodeSystem(shortName);
		codeSystemService.update(codeSystem, updateRequest);
		return findCodeSystem(shortName);
	}

	@ApiOperation(value = "Delete a code system", notes = "This function deletes the code system and its versions but it does not delete the branches or the content.")
	@RequestMapping(value = "/{shortName}", method = RequestMethod.DELETE)
	public void deleteCodeSystem(@PathVariable String shortName) {
		CodeSystem codeSystem = findCodeSystem(shortName);
		codeSystemService.deleteCodeSystemAndVersions(codeSystem);
	}

	@ApiOperation("Retrieve all code system versions")
	@RequestMapping(value = "/{shortName}/versions", method = RequestMethod.GET)
	public ItemsPage<CodeSystemVersion> findAllVersions(@PathVariable String shortName, @RequestParam(required = false) Boolean showFutureVersions) {
		return new ItemsPage<>(codeSystemService.findAllVersions(shortName, showFutureVersions));
	}

	@ApiOperation("Create a new code system version")
	@RequestMapping(value = "/{shortName}/versions", method = RequestMethod.POST)
	public ResponseEntity<Void> createVersion(@PathVariable String shortName, @RequestBody CreateCodeSystemVersionRequest input) {
		CodeSystem codeSystem = codeSystemService.find(shortName);
		ControllerHelper.throwIfNotFound("CodeSystem", codeSystem);

		String versionId = codeSystemService.createVersion(codeSystem, input.getEffectiveDate(), input.getDescription());
		return ControllerHelper.getCreatedResponse(versionId);
	}

	@ApiOperation(value = "Upgrade code system to a different dependant version.",
			notes = "This operation can be used to upgrade an extension to a new version of the parent code system. \n\n" +
					"If daily build is enabled for this code system that will be temporarily disabled and the daily build content will be rolled back automatically. \n\n" +
					"\n\n" +
					"The extension must have been imported on a branch which is a direct child of MAIN. \n\n" +
					"For example: MAIN/SNOMEDCT-BE. \n\n" +
					"_newDependantVersion_ uses the same format as the effectiveTime RF2 field, for example '20190731'. \n\n" +
					"_contentAutomations_ should be set to false unless you are the extension maintainer and would like some automatic content changes made " +
					"to support creating a new version of the extension. \n\n" +
					"If you are the extension maintainer an integrity check should be run after this operation to find content that needs fixing. ")
	@RequestMapping(value = "/{shortName}/upgrade", method = RequestMethod.POST)
	public void upgradeCodeSystem(@PathVariable String shortName, @RequestBody CodeSystemUpgradeRequest request) throws ServiceException {
		CodeSystem codeSystem = codeSystemService.findOrThrow(shortName);
		codeSystemUpgradeService.upgrade(codeSystem, request.getNewDependantVersion(), TRUE.equals(request.getContentAutomations()));
	}

	@ApiOperation(value = "DEPRECATED - Migrate code system to a different dependant version.",
			notes = "DEPRECATED in favour of upgrade operation. " +
					"This operation is required when an extension exists under an International version branch, for example: MAIN/2019-01-31/SNOMEDCT-BE. " +
					"An integrity check should be run after this operation to find content that needs fixing.")
	@RequestMapping(value = "/{shortName}/migrate", method = RequestMethod.POST)
	public void migrateCodeSystem(@PathVariable String shortName, @RequestBody CodeSystemMigrationRequest request) throws ServiceException {
		CodeSystem codeSystem = codeSystemService.find(shortName);
		ControllerHelper.throwIfNotFound("CodeSystem", codeSystem);

		codeSystemService.migrateDependantCodeSystemVersion(codeSystem, request.getDependantCodeSystem(), request.getNewDependantVersion(), request.isCopyMetadata());
	}

	@ApiOperation(value = "Rollback daily build commits.",
			notes = "If you have a daily build set up for a code system this operation should be used to revert/rollback the daily build content " +
					"before importing any versioned content. Be sure to disable the daily build too.")
	@RequestMapping(value = "/{shortName}/daily-build/rollback", method = RequestMethod.POST)
	public void rollbackDailyBuildContent(@PathVariable String shortName) {
		CodeSystem codeSystem = codeSystemService.find(shortName);
		dailyBuildService.rollbackDailyBuildContent(codeSystem);
	}

	@ApiOperation(value = "Generate additional english language refset",
			notes = "Before running this extensions must be upgraded already. " +
					"You must specify the branch path(e.g MAIN/SNOMEDCT-NZ/{project}/{task}) of the task for the delta to be added. " +
					"When completeCopy flag is set to true, all active en-gb language refset components will be copied into the extension module. " +
					"When completeCopy flag is set to false, only the changes from the latest international release will be copied/updated in the extension module. " +
					"Currently you only need to run this when upgrading SNOMEDCT-IE and SNOMEDCT-NZ")
	@RequestMapping(value = "/{shortName}/additional-en-language-refset-delta", method = RequestMethod.POST)
	public void generateAdditionalLanguageRefsetDelta(@PathVariable String shortName,
													  @RequestParam String branchPath,
													  @ApiParam("The language refset to copy from e.g 900000000000508004 | Great Britain English language reference set (foundation metadata concept) ")
													  @RequestParam (defaultValue = "900000000000508004") String languageRefsetToCopyFrom,
													  @ApiParam("Set completeCopy to true to copy all active components and false to copy only changes from the latest release.")
													  @RequestParam (defaultValue = "false") Boolean completeCopy) {

		ControllerHelper.requiredParam(shortName, "shortName");
		CodeSystem codeSystem = codeSystemService.find(shortName);
		if (codeSystem == null) {
			throw new NotFoundException("No code system found with short name " + shortName);
		}
		if (!BranchPathUriUtil.decodePath(branchPath).contains(codeSystem.getBranchPath())) {
			throw new IllegalArgumentException(String.format("Given branch %s must the code system branch %s or a child branch.", BranchPathUriUtil.decodePath(branchPath), codeSystem.getBranchPath()));
		}
		extensionAdditionalLanguageRefsetUpgradeService.generateAdditionalLanguageRefsetDelta(codeSystem, BranchPathUriUtil.decodePath(branchPath), languageRefsetToCopyFrom, completeCopy);
	}

	@ApiOperation("Clear cache of code system calculated/aggregated information.")
	@RequestMapping(value = "/clear-cache", method = RequestMethod.POST)
	@PreAuthorize("hasPermission('ADMIN', 'global')")
	public void clearCodeSystemInformationCache() {
		codeSystemService.clearCache();
	}
}
