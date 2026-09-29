/*
 * Copyright (c) 2026 PAYONE GmbH
 */
package com.payone.pcp.backoffice.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import de.hybris.bootstrap.annotations.UnitTest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.Before;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;


@UnitTest
public class PayoneBackofficeConfigurationTest
{
	private Document backofficeConfig;
	private Document widgetConfig;

	@Before
	public void setUp() throws Exception
	{
		backofficeConfig = loadXml("payonepcpbackoffice-backoffice-config.xml");
		widgetConfig = loadXml("payonepcpbackoffice-backoffice-widgets.xml");
	}

	@Test
	public void shouldRegisterOperationsWidgetOnlyOnAdministrationDashboard()
	{
		final Element widget = findElement(widgetConfig, "widget", "id", "payoneOperationsOverview");
		assertNotNull(widget);
		assertEquals("backoffice_dashboard", ((Element) widget.getParentNode()).getAttribute("widgetId"));
		assertNull(findElement(widgetConfig, "widget", "id", "payonepcpbackoffice-perspective"));

		assertTrue(hasConnection("explorerTree", "nodeIdSelected"));
		assertTrue(hasConnection("sideNavigation", "nodeIdSelected"));
	}

	@Test
	public void shouldPlaceOperationsImmediatelyBeforeMemoryOverview()
	{
		final Element operations = findElement(backofficeConfig, "placement", "widgetId", "payoneOperationsOverview");
		final Element memory = findElement(backofficeConfig, "placement", "widgetId", "defaulMemoryChart");
		assertNotNull(operations);
		assertNotNull(memory);
		assertEquals("2", operations.getAttribute("y"));
		assertEquals("2", operations.getAttribute("width"));
		assertEquals(Integer.parseInt(operations.getAttribute("y")) + 1, Integer.parseInt(memory.getAttribute("y")));
	}

	@Test
	public void shouldKeepPayoneNavigationAtTheRequestedPosition()
	{
		final Element payoneNode = findElement(backofficeConfig, "navigation-node", "id",
				"payonepcpbackoffice.treenode.payone");
		assertNotNull(payoneNode);
		assertEquals("13", payoneNode.getAttribute("position"));
	}

	@Test
	public void shouldNotExposeRecurringOrMaskedCardFields()
	{
		final Set<String> excludedQualifiers = Set.of(
				"firstRecurringPayment", "recurringToken", "mandate",
				"cardBrand", "maskedCardNumber", "cardholderName", "expiryDate");

		for (final String qualifier : excludedQualifiers)
		{
			assertNull("Unexpected Backoffice field: " + qualifier,
					findElement(backofficeConfig, null, "qualifier", qualifier));
		}
		assertFalse(hasAttributeContaining(backofficeConfig, "id", "recurring"));
		assertFalse(hasAttributeContaining(backofficeConfig, "id", "mandate"));
	}

	@Test
	public void shouldPackageStylesForCleanBackofficeDeployments() throws Exception
	{
		final Document buildCallbacks = loadExtensionXml("buildcallbacks.xml");
		final Element sassRegistration = findElement(buildCallbacks, "register_sass_extension",
				"extensionname", "payonepcpbackoffice");
		assertNotNull(sassRegistration);

		final Document widgetView = loadExtensionXml(
				"backoffice/resources/widgets/PayonepcpbackofficeWidget/payonepcpbackofficewidget.zul");
		assertNotNull(findElement(widgetView, "style", "src", "${wr}/payonepcpbackofficewidget.css"));

		assertTrue(Files.isRegularFile(findExtensionFile(
				"backoffice/resources/cng/css/payonepcpbackoffice_common.scss")));
		assertTrue(Files.isRegularFile(findExtensionFile(
				"backoffice/resources/cng/css/images/payone-icon-black.png")));
	}

	@Test
	public void shouldRegisterCaptureAndRefundOnlyOnCheckoutEditorWithoutCancel()
	{
		for (final String action : new String[] { "payonecheckoutcaptureaction", "payonecheckoutrefundaction" })
		{
			final Element element = findElement(backofficeConfig, "action", "action-id",
					"com.payone.pcp.backoffice.actions." + action);
			assertNotNull(action, element);
			final Element context = (Element) element.getParentNode().getParentNode().getParentNode();
			assertEquals("editorareaactions", context.getAttribute("component"));
			assertEquals("PayoneCheckout", context.getAttribute("type"));
			// Text next to the icon so operators can tell the two actions apart.
			assertTrue(action, element.getTextContent().replaceAll("\\s+", "").contains("viewModeiconAndText"));
		}
		assertFalse(hasAttributeContaining(backofficeConfig, "action-id", "payonecheckoutcancel"));
	}

	@Test
	public void captureAndRefundHaveDistinctIconsAndNames() throws Exception
	{
		final String captureIcon = iconOf("payonecheckoutcaptureaction");
		final String refundIcon = iconOf("payonecheckoutrefundaction");
		assertFalse(captureIcon.equals(refundIcon));
		for (final String action : new String[] { "payonecheckoutcaptureaction", "payonecheckoutrefundaction" })
		{
			final String dir = "backoffice/resources/widgets/actions/" + action + "/";
			assertTrue(action, Files.exists(findExtensionFile(dir + iconOf(action))));
			for (final String labels : new String[] { "labels.properties", "labels_en.properties", "labels_de.properties" })
			{
				assertTrue(action + " " + labels,
						Files.readString(findExtensionFile(dir + "labels/" + labels)).contains("actionName="));
			}
		}
	}

	private static String iconOf(final String action) throws Exception
	{
		return loadExtensionXml("backoffice/resources/widgets/actions/" + action + "/definition.xml")
				.getElementsByTagName("iconUri").item(0).getTextContent().trim();
	}

	private boolean hasConnection(final String targetWidgetId, final String inputId)
	{
		final NodeList connections = widgetConfig.getElementsByTagName("widget-connection");
		for (int index = 0; index < connections.getLength(); index++)
		{
			final Element connection = (Element) connections.item(index);
			if ("payoneOperationsOverview".equals(connection.getAttribute("sourceWidgetId"))
					&& targetWidgetId.equals(connection.getAttribute("targetWidgetId"))
					&& inputId.equals(connection.getAttribute("inputId")))
			{
				return true;
			}
		}
		return false;
	}

	private static boolean hasAttributeContaining(final Document document, final String attribute, final String text)
	{
		final NodeList elements = document.getElementsByTagName("*");
		for (int index = 0; index < elements.getLength(); index++)
		{
			final Element element = (Element) elements.item(index);
			if (element.getAttribute(attribute).toLowerCase().contains(text.toLowerCase()))
			{
				return true;
			}
		}
		return false;
	}

	private static Element findElement(final Document document, final String localName,
			final String attribute, final String value)
	{
		final NodeList elements = document.getElementsByTagName("*");
		for (int index = 0; index < elements.getLength(); index++)
		{
			final Node node = elements.item(index);
			if (node instanceof Element)
			{
				final Element element = (Element) node;
				final boolean nameMatches = localName == null || localName.equals(element.getLocalName());
				if (nameMatches && value.equals(element.getAttribute(attribute)))
				{
					return element;
				}
			}
		}
		return null;
	}

	private static Document loadXml(final String resourceName) throws Exception
	{
		try (InputStream input = openResource(resourceName))
		{
			return parseXml(input);
		}
	}

	private static Document loadExtensionXml(final String relativePath) throws Exception
	{
		try (InputStream input = Files.newInputStream(findExtensionFile(relativePath)))
		{
			return parseXml(input);
		}
	}

	private static Document parseXml(final InputStream input) throws Exception
	{
		final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setNamespaceAware(true);
		factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
		return factory.newDocumentBuilder().parse(input);
	}

	private static InputStream openResource(final String resourceName) throws Exception
	{
		final InputStream classpathResource = Thread.currentThread().getContextClassLoader()
				.getResourceAsStream(resourceName);
		if (classpathResource != null)
		{
			return classpathResource;
		}

		return Files.newInputStream(findExtensionFile("resources/" + resourceName));
	}

	private static Path findExtensionFile(final String relativePath) throws Exception
	{
		Path location = Paths.get(PayoneBackofficeConfigurationTest.class.getProtectionDomain()
				.getCodeSource().getLocation().toURI());
		if (Files.isRegularFile(location))
		{
			location = location.getParent();
		}

		for (Path directory = location; directory != null; directory = directory.getParent())
		{
			final Path candidate = directory.resolve(relativePath);
			if (Files.isRegularFile(candidate))
			{
				return candidate;
			}
		}

		throw new IOException("Missing extension file: " + relativePath);
	}
}
