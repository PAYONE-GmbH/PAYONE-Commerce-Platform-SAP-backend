package com.payone.pcp.core.service.impl;

import com.payone.commerce.platform.lib.errors.ApiErrorResponseException;
import com.payone.commerce.platform.lib.errors.ApiResponseRetrievalException;
import com.payone.commerce.platform.lib.models.APIError;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.StringJoiner;


/**
 * The SDK's checked exceptions get wrapped in IllegalStateException upstream; this gives
 * callers (facade/strategy/webhook handlers) a category to decide retry vs. fail vs. escalate,
 * with consistent log levels.
 */
public final class PayoneApiErrorMapper
{
	private static final Logger LOG = LoggerFactory.getLogger(PayoneApiErrorMapper.class);
	private static final int MAX_ERRORS_TO_LOG = 10;
	private static final int MAX_FIELD_LENGTH = 512;

	public enum ErrorCategory
	{
		/** Configuration error - deployment fix needed, not retryable. */
		CONFIGURATION,
		/** Valid HTTP error response from PCP (4xx). Logged as warning. */
		API_ERROR,
		/** Network or timeout error (IOException, ApiResponseRetrievalException). Potentially retryable. */
		NETWORK,
		/** Unexpected error. Logged as error. */
		UNKNOWN
	}

	private PayoneApiErrorMapper()
	{
		// static utility
	}

	/** Categorises and logs a throwable from the PCP SDK. */
	public static ErrorCategory categorise(final String operation, final String merchantId, final Throwable t)
	{
        switch (t) {
            case null -> {
                return ErrorCategory.UNKNOWN;
            }
            case IllegalArgumentException illegalArgumentException -> {
                LOG.warn("PCP [{}] configuration error for merchantId [{}]: {}", operation, merchantId, illegalArgumentException.getMessage());
                return ErrorCategory.CONFIGURATION;
            }
            case ApiErrorResponseException apiErr -> {
				LOG.warn("PCP [{}] API error for merchantId [{}]: {}",
						operation, merchantId, describe(apiErr));
                return ErrorCategory.API_ERROR;
            }
            case ApiResponseRetrievalException apiResponseRetrievalException -> {
                LOG.error("PCP [{}] network error for merchantId [{}]: {}", operation, merchantId, apiResponseRetrievalException.getMessage(), t);
                return ErrorCategory.NETWORK;
            }
            case IOException ioException -> {
                LOG.error("PCP [{}] network error for merchantId [{}]: {}", operation, merchantId, ioException.getMessage(), t);
                return ErrorCategory.NETWORK;
            }
            default -> {
				LOG.error("PCP [{}] unexpected error for merchantId [{}]: {}", operation, merchantId, t.getMessage(), t);
				return ErrorCategory.UNKNOWN;
			}
		}

	}

	/**
	 * Builds a single-line, bounded diagnostic for a PCP SDK failure.
	 *
	 * The raw response body is deliberately excluded because a provider response
	 * can contain values that must not be copied into application logs. For API
	 * errors we log only the SDK's structured error metadata. Control characters
	 * are removed to prevent multiline/log-injection output and every field is
	 * capped so a provider response cannot flood the log.
	 */
	public static String describe(final Throwable throwable)
	{
		final ApiErrorResponseException apiError = findApiError(throwable);
		if (apiError != null)
		{
			return describeApiError(apiError);
		}

		final Throwable rootCause = findRootCause(throwable);
		return "type=" + rootCause.getClass().getSimpleName()
				+ ", message=" + safe(rootCause.getMessage());
	}

	private static String describeApiError(final ApiErrorResponseException apiError)
	{
		final List<APIError> errors = apiError.getErrors();
		final int errorCount = errors == null ? 0 : errors.size();
		final StringJoiner formattedErrors = new StringJoiner(", ", "[", "]");

		if (errors != null)
		{
			for (int index = 0; index < Math.min(errorCount, MAX_ERRORS_TO_LOG); index++)
			{
				final APIError error = errors.get(index);
				if (error == null)
				{
					formattedErrors.add("{index=" + index + ", value=N/A}");
					continue;
				}

				formattedErrors.add("{index=" + index
						+ ", id=" + safe(error.getId())
						+ ", errorCode=" + safe(error.getErrorCode())
						+ ", category=" + safe(error.getCategory())
						+ ", httpStatusCode=" + safe(error.getHttpStatusCode())
						+ ", propertyName=" + safe(error.getPropertyName())
						+ ", message=" + safe(error.getMessage())
						+ "}");
			}
		}

		if (errorCount > MAX_ERRORS_TO_LOG)
		{
			formattedErrors.add("{omitted=" + (errorCount - MAX_ERRORS_TO_LOG) + "}");
		}

		return "type=" + apiError.getClass().getSimpleName()
				+ ", httpStatus=" + apiError.getStatusCode()
				+ ", errorCount=" + errorCount
				+ ", errors=" + formattedErrors;
	}

	/**
	 * Resolves the most useful exception message for surfacing to a caller as
	 * an IllegalStateException: an ApiErrorResponseException's structured
	 * error detail (via {@link #describe}) if the throwable chain contains
	 * one (its own {@code getMessage()} is often null - the real detail
	 * lives on {@code getErrors()}), else the deepest non-blank message in
	 * the chain, else a generic fallback.
	 */
	public static String rootCauseMessage(final Throwable throwable)
	{
		final ApiErrorResponseException apiError = findApiError(throwable);
		if (apiError != null)
		{
			return describe(apiError);
		}

		Throwable current = throwable;
		String message = throwable == null ? null : throwable.getMessage();

		while (current != null)
		{
			if (StringUtils.isNotBlank(current.getMessage()))
			{
				message = current.getMessage();
			}
			current = current.getCause();
		}

		return StringUtils.defaultIfBlank(message, "PAYONE request failed");
	}

	private static ApiErrorResponseException findApiError(final Throwable throwable)
	{
		Throwable current = throwable;
		while (current != null)
		{
			if (current instanceof ApiErrorResponseException apiError)
			{
				return apiError;
			}
			current = current.getCause();
		}
		return null;
	}

	private static Throwable findRootCause(final Throwable throwable)
	{
		if (throwable == null)
		{
			return new IllegalStateException("unknown PCP failure");
		}

		Throwable current = throwable;
		while (current.getCause() != null && current.getCause() != current)
		{
			current = current.getCause();
		}
		return current;
	}

	private static String safe(final Object value)
	{
		if (value == null)
		{
			return "N/A";
		}

		final String singleLine = String.valueOf(value)
				.replace('\r', ' ')
				.replace('\n', ' ')
				.replace('\t', ' ')
				.trim();
		if (singleLine.isEmpty())
		{
			return "N/A";
		}
		return singleLine.length() <= MAX_FIELD_LENGTH
				? singleLine
				: singleLine.substring(0, MAX_FIELD_LENGTH) + "...";
	}
}
