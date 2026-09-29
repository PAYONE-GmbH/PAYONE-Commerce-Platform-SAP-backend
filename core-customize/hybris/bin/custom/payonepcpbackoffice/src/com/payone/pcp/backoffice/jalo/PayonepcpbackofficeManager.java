/*
 * Copyright (c) 2023 SAP SE or an SAP affiliate company. All rights reserved
 */
package com.payone.pcp.backoffice.jalo;

import com.payone.pcp.backoffice.constants.PayonepcpbackofficeConstants;
import de.hybris.platform.jalo.JaloSession;
import de.hybris.platform.jalo.extension.ExtensionManager;

public class PayonepcpbackofficeManager extends GeneratedPayonepcpbackofficeManager
{
	public static final PayonepcpbackofficeManager getInstance()
	{
		ExtensionManager em = JaloSession.getCurrentSession().getExtensionManager();
		return (PayonepcpbackofficeManager) em.getExtension(PayonepcpbackofficeConstants.EXTENSIONNAME);
	}
	
}
