package org.snomed.snowstorm.rest;

import io.kaicode.rest.util.branchpathrewrite.BranchPathUriUtil;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.snomed.snowstorm.config.Config;
import org.snomed.snowstorm.core.data.domain.ConceptMini;
import org.snomed.snowstorm.core.data.services.ServiceException;
import org.snomed.snowstorm.mrcm.MRCMService;
import org.snomed.snowstorm.mrcm.model.ContentType;
import org.snomed.snowstorm.rest.pojo.ItemsPage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

import static org.snomed.snowstorm.rest.ControllerHelper.parseAcceptLanguageHeaderWithDefaultFallback;

@RestController
@Api(tags = "MRCM", description = "-")
@RequestMapping(produces = "application/json")
public class MRCMController {

	@Autowired
	private MRCMService mrcmService;

	@ApiOperation(value = "Retrieve MRCM domain attributes applicable for the given stated parents.",
			notes = "The parentIds must be the set ids of stated parents. If creating post-coordinated expressions be sure to set the content type to POSTCOORDINATED.")
	@RequestMapping(value = "/mrcm/{branch}/domain-attributes", method = RequestMethod.GET)
	public ItemsPage<ConceptMini> retrieveDomainAttributes(
			@PathVariable String branch,
			@RequestParam(required = false) Set<Long> parentIds,
			@RequestParam(required = false, defaultValue = "true") boolean proximalPrimitiveModeling,
			@RequestParam(required = false, defaultValue = "NEW_PRECOORDINATED") ContentType contentType,
			@RequestHeader(value = "Accept-Language", defaultValue = Config.DEFAULT_ACCEPT_LANG_HEADER) String acceptLanguageHeader) throws ServiceException {

		branch = BranchPathUriUtil.decodePath(branch);
		return new ItemsPage<>(mrcmService.retrieveDomainAttributes(contentType, proximalPrimitiveModeling, parentIds, branch, parseAcceptLanguageHeaderWithDefaultFallback(acceptLanguageHeader)));
	}

	@ApiOperation("Retrieve valid values for the given attribute and term prefix.")
	@RequestMapping(value = "/mrcm/{branch}/attribute-values/{attributeId}", method = RequestMethod.GET)
	public ItemsPage<ConceptMini> retrieveAttributeValues(
			@PathVariable String branch,
			@RequestParam(required = false, defaultValue = "NEW_PRECOORDINATED") ContentType contentType,
			@PathVariable String attributeId,
			@RequestParam String termPrefix,
			@RequestHeader(value = "Accept-Language", defaultValue = Config.DEFAULT_ACCEPT_LANG_HEADER) String acceptLanguageHeader) throws ServiceException {

		branch = BranchPathUriUtil.decodePath(branch);
		return new ItemsPage<>(mrcmService.retrieveAttributeValues(contentType, attributeId, termPrefix, branch, parseAcceptLanguageHeaderWithDefaultFallback(acceptLanguageHeader)));
	}

	@ApiOperation("Retrieve all active concept model attributes in a hierarchical structure.")
	@RequestMapping(value = "/mrcm/{branch}/concept-model-attribute-hierarchy", method = RequestMethod.GET)
	public ConceptMini retrieveConceptModelAttributeHierarchy(
			@PathVariable String branch,
			@RequestHeader(value = "Accept-Language", defaultValue = Config.DEFAULT_ACCEPT_LANG_HEADER) String acceptLanguageHeader) {

		branch = BranchPathUriUtil.decodePath(branch);
		return mrcmService.retrieveConceptModelAttributeHierarchy(branch, parseAcceptLanguageHeaderWithDefaultFallback(acceptLanguageHeader));
	}

}
