package com.payone.pcp.core.converters;

import org.apache.commons.lang3.StringUtils;


/**
 * Truncates SAP-Commerce-derived strings to the maxLength (or x-trim-at) declared for the
 * corresponding PCP API field (docs.commerce.payone.com/api-reference,
 * PayoneCommercePlattform.yaml).
 * <p>
 * Only for fields sourced from arbitrary SAP data (names, addresses, emails, product data,
 * references), where PCP rejects or silently truncates an over-length value. Never apply this
 * to pattern-constrained, frontend-supplied values (IBAN, mandate/creditor ids) - truncation
 * would corrupt those instead of fitting them.
 */
public final class PcpFieldTrimmer
{
	private PcpFieldTrimmer()
	{
		// utility class
	}

	/**
	 * Truncates {@code value} to at most {@code maxLength} characters.
	 *
	 * @param value     the value to truncate; may be null
	 * @param maxLength the maximum length declared for this field in the PCP API reference
	 * @return {@code value} truncated to {@code maxLength} characters, or null if {@code value} is null
	 */
	public static String trim(final String value, final int maxLength)
	{
		if (value == null)
		{
			return null;
		}
		return value.length() > maxLength ? value.substring(0, maxLength) : value;
	}

	/**
	 * Same as {@link #trim(String, int)}, but first trims whitespace and returns null for a
	 * blank result, for fields where PCP treats an empty string as "not provided".
	 *
	 * @param value     the value to truncate; may be null
	 * @param maxLength the maximum length declared for this field in the PCP API reference
	 * @return the trimmed and truncated value, or null if {@code value} is null or blank
	 */
	public static String trimToNull(final String value, final int maxLength)
	{
		final String trimmed = StringUtils.trimToNull(value);
		return trim(trimmed, maxLength);
	}
}
