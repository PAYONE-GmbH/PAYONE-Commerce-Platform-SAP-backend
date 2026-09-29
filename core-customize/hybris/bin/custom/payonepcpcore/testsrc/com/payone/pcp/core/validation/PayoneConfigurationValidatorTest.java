package com.payone.pcp.core.validation;

import de.hybris.bootstrap.annotations.UnitTest;

import com.payone.pcp.core.model.PayoneConfigurationModel;

import java.util.List;

import org.junit.Test;

import static org.junit.Assert.*;


@UnitTest
public class PayoneConfigurationValidatorTest
{
	// merchantId charset

	@Test
	public void shouldAcceptValidMerchantId()
	{
		assertNoViolations(configWithMerchantId("ACME_SHOP_DE"));
		assertNoViolations(configWithMerchantId("acme-shop-de"));
		assertNoViolations(configWithMerchantId("Merchant123"));
		assertNoViolations(configWithMerchantId("A"));
		assertNoViolations(configWithMerchantId("1"));
	}

	@Test
	public void shouldRejectMerchantIdWithSpaces()
	{
		final List<String> violations = validate(configWithMerchantId("my merchant"));
		assertContains("merchantId", violations.getFirst());
		assertContains("invalid characters", violations.getFirst());
	}

	@Test
	public void shouldRejectMerchantIdWithSpecialCharacters()
	{
		final List<String> violations = validate(configWithMerchantId("merchant$%&/"));
		assertContains("invalid characters", violations.getFirst());
	}

	@Test
	public void shouldRejectMerchantIdWithUmlauts()
	{
		final List<String> violations = validate(configWithMerchantId("Märchant"));
		assertContains("invalid characters", violations.getFirst());
	}

	@Test
	public void shouldRejectMerchantIdWithChineseCharacters()
	{
		final List<String> violations = validate(configWithMerchantId("商户"));
		assertContains("invalid characters", violations.getFirst());
	}

	// merchantId length

	@Test
	public void shouldRejectOverlyLongMerchantId()
	{
		final String longId = "a".repeat(PayoneConfigurationValidator.MERCHANT_ID_MAX_LENGTH + 1);
		final List<String> violations = validate(configWithMerchantId(longId));
		assertContains("exceeds maximum length", violations.getFirst());
	}

	@Test
	public void shouldAcceptMaximumLengthMerchantId()
	{
		final String maxId = "a".repeat(PayoneConfigurationValidator.MERCHANT_ID_MAX_LENGTH);
		assertNoViolations(configWithMerchantId(maxId));
	}

	@Test
	public void shouldRejectBothCharsetAndLengthViolations()
	{
		final String bad = "a".repeat(PayoneConfigurationValidator.MERCHANT_ID_MAX_LENGTH + 1) + " ";
		final List<String> violations = validate(configWithMerchantId(bad));
		assertEquals(2, violations.size());
	}

	// returnUrl format

	@Test
	public void shouldAcceptNullReturnUrl()
	{
		assertNoViolations(configWithReturnUrl(null));
	}

	@Test
	public void shouldAcceptHttpsReturnUrl()
	{
		assertNoViolations(configWithReturnUrl("https://shop.example.com/payone/return"));
	}

	@Test
	public void shouldRejectNonHttpsReturnUrl()
	{
		assertContains("returnUrl", validate(configWithReturnUrl("http://shop.example.com/return")).getFirst());
	}

	@Test
	public void shouldRejectMalformedReturnUrl()
	{
		assertContains("returnUrl", validate(configWithReturnUrl("not a url")).getFirst());
	}

	@Test
	public void shouldRejectReturnUrlWithFragment()
	{
		assertContains("returnUrl", validate(configWithReturnUrl("https://shop.example.com/return#fragment")).getFirst());
	}

	@Test
	public void shouldRejectBlankReturnUrl()
	{
		assertContains("returnUrl", validate(configWithReturnUrl(" ")).getFirst());
	}

	// apiEndpointHost format

	@Test
	public void shouldAcceptValidEndpointHost()
	{
		assertNoViolations(configWithEndpoint("https://api.preprod.commerce.payone.com"));
		assertNoViolations(configWithEndpoint("api.preprod.commerce.payone.com"));
		assertNoViolations(configWithEndpoint("https://api.commerce.payone.com:8443/v1"));
	}

	@Test
	public void shouldRejectEndpointHostThatIsTooShort()
	{
		final List<String> violations = validate(configWithEndpoint("short"));
		assertContains("too short", violations.getFirst());
	}

	@Test
	public void shouldRejectEndpointHostThatIsNotAUrl()
	{
		final List<String> violations = validate(configWithEndpoint(""));
		// Empty string fails both "too short" and "not a valid URL"
        assertFalse(violations.isEmpty());
	}

	@Test
	public void shouldRejectEndpointHostWithJustASlash()
	{
		final List<String> violations = validate(configWithEndpoint("/"));
		assertContains("too short", violations.getFirst());
	}

	@Test
	public void shouldReportAllViolationsTogether()
	{
		// Bad merchantId + bad endpoint = 2 violations
		final PayoneConfigurationModel model = new PayoneConfigurationModel();
		model.setMerchantId("bad merchant id!!!");
		model.setApiEndpointHost("x"); // x can be a valid host. like in https://x

		final List<String> violations = validate(model);
		assertEquals(2, violations.size());  // charset + length + too short
	}

	// Null-safe

	@Test
	public void shouldPassWhenMerchantIdIsNull()
	{
		final PayoneConfigurationModel model = new PayoneConfigurationModel();
		model.setApiEndpointHost("https://api.preprod.commerce.payone.com");
		assertNoViolations(model);
	}

	@Test
	public void shouldPassWhenEndpointHostIsNull()
	{
		final PayoneConfigurationModel model = new PayoneConfigurationModel();
		model.setMerchantId("VALID_MERCHANT");
		assertNoViolations(model);
	}

	@Test
	public void shouldPassForNullModel()
	{
		assertThrows(NullPointerException.class, () -> PayoneConfigurationValidator.validate(null));
	}

	// isValidUrl (static helper coverage)

	@Test
	public void isValidUrlShouldAcceptValidUrls()
	{
		assertTrue(PayoneConfigurationValidator.isValidUrl("https://api.preprod.commerce.payone.com"));
		assertTrue(PayoneConfigurationValidator.isValidUrl("http://localhost:9002"));
		assertTrue(PayoneConfigurationValidator.isValidUrl("api.preprod.commerce.payone.com"));
	}

	@Test
	public void isValidUrlShouldRejectInvalidUrls()
	{
        assertFalse("empty string is not a valid URL", PayoneConfigurationValidator.isValidUrl(""));
        assertFalse("just slash is not a valid URL host", PayoneConfigurationValidator.isValidUrl("/"));
        assertFalse("null is not a valid URL", PayoneConfigurationValidator.isValidUrl(null));
	}

	// Helpers

	private static List<String> validate(final PayoneConfigurationModel model)
	{
		return PayoneConfigurationValidator.validate(model);
	}

	private static PayoneConfigurationModel configWithMerchantId(final String merchantId)
	{
		final PayoneConfigurationModel model = new PayoneConfigurationModel();
		model.setMerchantId(merchantId);
		model.setApiEndpointHost("https://api.preprod.commerce.payone.com");
		return model;
	}

	private static PayoneConfigurationModel configWithReturnUrl(final String returnUrl)
	{
		final PayoneConfigurationModel model = new PayoneConfigurationModel();
		model.setMerchantId("VALID_MERCHANT");
		model.setApiEndpointHost("https://api.preprod.commerce.payone.com");
		model.setReturnUrl(returnUrl);
		return model;
	}

	private static PayoneConfigurationModel configWithEndpoint(final String endpoint)
	{
		final PayoneConfigurationModel model = new PayoneConfigurationModel();
		model.setMerchantId("VALID_MERCHANT");
		model.setApiEndpointHost(endpoint);
		return model;
	}

	private static void assertNoViolations(final PayoneConfigurationModel model)
	{
		final List<String> violations = validate(model);
		assertTrue("Expected no violations but got: " + violations, violations.isEmpty());
	}

	private static void assertContains(final String expected, final String actual)
	{
		assertTrue("Expected [" + actual + "] to contain [" + expected + "]", actual.contains(expected));
	}
}
