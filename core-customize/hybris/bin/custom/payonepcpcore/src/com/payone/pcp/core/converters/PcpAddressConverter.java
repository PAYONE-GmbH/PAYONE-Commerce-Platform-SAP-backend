package com.payone.pcp.core.converters;

import de.hybris.platform.core.model.user.AddressModel;

import com.payone.commerce.platform.lib.models.Address;
import com.payone.commerce.platform.lib.models.AddressPersonal;
import com.payone.commerce.platform.lib.models.PersonalName;

import java.util.Objects;


/** Converts SAP AddressModel to PCP SDK Address and AddressPersonal. */
public class PcpAddressConverter
{
	/**
	 * Converts an SAP AddressModel to a PCP Address. {@code houseNumber} is a distinct PCP
	 * field (docs.commerce.payone.com/api-reference), populated from
	 * {@code AddressModel.streetnumber}.
	 *
	 * @param sapAddress the SAP address; must not be null
	 * @return PCP Address
	 * @throws IllegalArgumentException if sapAddress is null
	 */
	public Address toAddress(final AddressModel sapAddress)
	{
		Objects.requireNonNull(sapAddress, "sapAddress must not be null");

		// Address maxLength (api-reference): street 50, houseNumber 10, city 40, zip 10, state 3.
		final Address address = new Address()
				.street(PcpFieldTrimmer.trim(sapAddress.getStreetname(), 50))
				.houseNumber(PcpFieldTrimmer.trimToNull(sapAddress.getStreetnumber(), 10))
				.city(PcpFieldTrimmer.trim(sapAddress.getTown(), 40))
				.zip(PcpFieldTrimmer.trim(sapAddress.getPostalcode(), 10))
				.countryCode(resolveCountryCode(sapAddress));

		if (sapAddress.getRegion() != null)
		{
			address.setState(PcpFieldTrimmer.trim(sapAddress.getRegion().getIsocodeShort(), 3));
		}

		return address;
	}

	/**
	 * Converts an SAP AddressModel to a PCP AddressPersonal with name details.
	 * <p>
	 * AddressPersonal and Address are unrelated SDK types with no common supertype but share
	 * the same 5 base fields with the same maxLengths (api-reference), so this delegates to
	 * {@link #toAddress} and copies the fields across.
	 *
	 * @param sapAddress the SAP address; must not be null
	 * @param firstName  the personal first name; may be null
	 * @param surname    the personal surname; may be null
	 * @return PCP AddressPersonal
	 * @throws IllegalArgumentException if sapAddress is null
	 */
	public AddressPersonal toAddressPersonal(
			final AddressModel sapAddress,
			final String firstName,
			final String surname)
	{
		Objects.requireNonNull(sapAddress, "sapAddress must not be null");

		final Address address = toAddress(sapAddress);

		// PersonalName.firstName/surname: x-trim-at 35 (api-reference).
		return new AddressPersonal()
				.street(address.getStreet())
				.houseNumber(address.getHouseNumber())
				.city(address.getCity())
				.zip(address.getZip())
				.countryCode(address.getCountryCode())
				.state(address.getState())
				.name(new PersonalName()
						.firstName(PcpFieldTrimmer.trim(firstName, 35))
						.surname(PcpFieldTrimmer.trim(surname, 35)));
	}

	private static String resolveCountryCode(final AddressModel address)
	{
		if (address.getCountry() == null)
		{
			return null;
		}
		return address.getCountry().getIsocode().toUpperCase();
	}
}
