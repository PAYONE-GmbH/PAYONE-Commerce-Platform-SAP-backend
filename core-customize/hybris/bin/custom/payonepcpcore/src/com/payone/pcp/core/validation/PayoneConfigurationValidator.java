package com.payone.pcp.core.validation;

import com.payone.pcp.core.model.PayoneConfigurationModel;


import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;


/**
 * Static validators for PayoneConfiguration type constraints. Runs at the service layer
 * rather than a model interceptor, since checks like merchantId need the full config context,
 * not just the single attribute. Stateless and thread-safe.
 * <p>
 * Each validator returns {@code null} on success or a human-readable message explaining the
 * violation; {@link #validate} collects all violations so an operator gets the full picture
 * in one pass instead of fixing fields one at a time.
 */
public final class PayoneConfigurationValidator {
    /**
     * PAYONE merchant IDs are opaque tokens with an unpublished format, but the PCP API
     * accepts ASCII alphanumerics, underscores and hyphens. Null is not validated here - it
     * may just mean the store isn't configured yet.
     */
    // Visible for testing
    static final Pattern MERCHANT_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]+");

    /**
     * Sanity cap, not a PAYONE-documented limit - catches an operator pasting a whole PEM
     * block into the merchantId field.
     */
    static final int MERCHANT_ID_MAX_LENGTH = 128;

    /**
     * Shortest plausible PCP hostname ("api.preprod.commerce.payone.com") is well over this;
     * catches pasting the PAYONE Portal URL or a leftover "replaceme" default.
     */
    static final int API_ENDPOINT_HOST_MIN_LENGTH = 10;

    private PayoneConfigurationValidator() {
        // static-only utility class
    }

    /**
     * Validates all type constraints on a PayoneConfigurationModel.
     *
     * @param model the configuration model to validate; must not be {@code null}.
     * @return violation messages, one per failed constraint; empty if all pass. Never {@code null}.
     */
    public static List<String> validate(final PayoneConfigurationModel model) {
        Objects.requireNonNull(model, "PayoneConfigurationModel must not be null");
        final List<String> violations = new ArrayList<>();

        final String merchantId = model.getMerchantId();
        if (merchantId != null) {
            if (!MERCHANT_ID_PATTERN.matcher(merchantId).matches()) {
                violations.add("merchantId [" + merchantId + "] contains invalid characters. " + "Only ASCII letters, digits, underscores and hyphens are allowed.");
            }
            if (merchantId.length() > MERCHANT_ID_MAX_LENGTH) {
                violations.add("merchantId [" + merchantId + "] exceeds maximum length of " + MERCHANT_ID_MAX_LENGTH + " characters (actual: " + merchantId.length() + ").");
            }
        }

        final String returnUrl = model.getReturnUrl();
        if (returnUrl != null && !isValidReturnUrl(returnUrl)) {
            violations.add("returnUrl [" + returnUrl + "] must be an absolute HTTPS URL with a host and no fragment.");
        }

        final String endpointHost = model.getApiEndpointHost();
        if (endpointHost != null) {
            // Check minimum length first so the pattern test below doesn't NPE on trim
            if (endpointHost.trim().length() < API_ENDPOINT_HOST_MIN_LENGTH) {
                violations.add("apiEndpointHost [" + endpointHost + "] is too short (min " + API_ENDPOINT_HOST_MIN_LENGTH + " characters). " + "Expected a PAYONE Commerce Platform API host like " + "\"https://api.preprod.commerce.payone.com\".");
            }

            if (!isValidUrl(endpointHost)) {
                violations.add("apiEndpointHost [" + endpointHost + "] is not a valid URL. Ensure the value is a scheme + host " + "(e.g. https://api.preprod.commerce.payone.com).");
            }
        }

        return violations;
    }

    /**
     * Strict validation for stable PCP redirect configuration. A non-null value
     * must use HTTPS, include a host and omit fragments.
     */
    static boolean isValidReturnUrl(final String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            final URI uri = new URI(url);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && uri.isAbsolute()
                    && uri.getHost() != null
                    && !uri.getHost().isBlank()
                    && uri.getFragment() == null;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /**
     * Quick URL sanity check for apiEndpointHost.
     */
    static boolean isValidUrl(final String url) {
        if (url == null || url.isBlank()) {
            return false;
        }

        final String value = url.contains("://") ? url : "https://" + url;

        try {
            final URI uri = new URI(value);

            if (uri.getScheme() == null || (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))) {
                return false;
            }

            return uri.getHost() != null && !uri.getHost().isBlank();
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
