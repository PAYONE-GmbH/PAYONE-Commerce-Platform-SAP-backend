package com.payone.pcp.core.setup;

import static com.payone.pcp.core.constants.PayonepcpcoreConstants.EXTENSIONNAME;

import de.hybris.platform.commerceservices.setup.AbstractSystemSetup;
import de.hybris.platform.core.initialization.SystemSetup;
import de.hybris.platform.core.initialization.SystemSetup.Process;
import de.hybris.platform.core.initialization.SystemSetup.Type;
import de.hybris.platform.core.initialization.SystemSetupContext;
import de.hybris.platform.core.initialization.SystemSetupParameter;
import de.hybris.platform.core.initialization.SystemSetupParameterMethod;

import java.util.ArrayList;
import java.util.List;

/**
 * Imports PCP sample data during system initialization / update.
 * <p>
 * {@code createProjectData} runs unconditionally on {@code ant initialize} (every extension gets
 * {@code <extension>_sample=true}), so a CCv2 "Initialize database" deployment hits this on every
 * environment including production - hence {@link #IMPORT_SAMPLE_CONFIG_DEFAULT} must stay false.
 * On {@code ant updatesystem} it only runs for extensions listed in
 * {@code update.executeProjectData.extensionName.list}, which is unset by default, so a "Migrate
 * data" deployment typically skips it entirely (no log line, nothing to see in the build output).
 * In HAC it runs whenever the per-extension checkbox on the Initialize/Update screen is checked.
 */
@SystemSetup(extension = EXTENSIONNAME)
public class PayonepcpcoreSystemSetup extends AbstractSystemSetup
{
	/**
	 * Opt-in flag controlling import of the sample PayoneConfiguration. Submitted
	 * by HAC as {@code payonepcpcore_importPayoneSampleConfig}.
	 */
	public static final String IMPORT_SAMPLE_CONFIG = "importPayoneSampleConfig";

	/**
	 * Default for {@link #IMPORT_SAMPLE_CONFIG}. Must stay {@code false} - see the class Javadoc.
	 */
	private static final boolean IMPORT_SAMPLE_CONFIG_DEFAULT = false;

	private static final String PAYMENT_MODES_IMPEX =
			"/payonepcpcore/import/projectdata/projectdata-paymentmodes.impex";

	private static final String SAMPLE_CONFIG_IMPEX =
			"/payonepcpcore/import/sampledata/payone-sample-configuration.impex";

	/**
	 * Options offered on the HAC init &amp; update screens; also where
	 * {@code getDefaultValueForBooleanSystemSetupParameter} reads its fallback from.
	 */
	@Override
	@SystemSetupParameterMethod
	public List<SystemSetupParameter> getInitializationOptions()
	{
		final List<SystemSetupParameter> params = new ArrayList<>();
		params.add(createBooleanSystemSetupParameter(IMPORT_SAMPLE_CONFIG,
				"Import PAYONE sample configuration (dev/demo only)", IMPORT_SAMPLE_CONFIG_DEFAULT));
		return params;
	}

	/**
	 * Imports payment modes unconditionally, then the sample configuration if opted in. Idempotent,
	 * safe to rerun. May not run at all on {@code ant updatesystem} - see the class Javadoc.
	 *
	 * @param context the selected setup parameters/values
	 */
	@SystemSetup(type = Type.PROJECT, process = Process.ALL)
	public void createProjectData(final SystemSetupContext context)
	{
		importImpexFile(context, PAYMENT_MODES_IMPEX);

		if (getBooleanSystemSetupParameter(context, IMPORT_SAMPLE_CONFIG))
		{
			importImpexFile(context, SAMPLE_CONFIG_IMPEX);
		}
	}
}
