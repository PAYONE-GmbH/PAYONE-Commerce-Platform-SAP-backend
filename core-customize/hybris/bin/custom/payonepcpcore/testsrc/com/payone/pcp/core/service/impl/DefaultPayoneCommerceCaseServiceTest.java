package com.payone.pcp.core.service.impl;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.hybris.bootstrap.annotations.UnitTest;

import com.payone.commerce.platform.lib.endpoints.CommerceCaseApiClient;
import com.payone.commerce.platform.lib.models.CommerceCaseResponse;
import com.payone.commerce.platform.lib.models.CreateCommerceCaseRequest;
import com.payone.commerce.platform.lib.models.CreateCommerceCaseResponse;

import com.payone.pcp.core.model.PayoneConfigurationModel;
import com.payone.pcp.core.service.PayonePcpClientFactory;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;


@UnitTest
public class DefaultPayoneCommerceCaseServiceTest {
    private static final String MERCHANT_ID = "TEST_MERCHANT";

    @Mock
    private PayonePcpClientFactory clientFactory;

    @Mock
    private CommerceCaseApiClient apiClient;

    private DefaultPayoneCommerceCaseService service;
    private PayoneConfigurationModel config;

    private AutoCloseable mocks;

    @Before
    public void setUp() {
        mocks = MockitoAnnotations.openMocks(this);

        when(clientFactory.createCommerceCaseApiClient(any())).thenReturn(apiClient);

        service = new DefaultPayoneCommerceCaseService();
        service.setClientFactory(clientFactory);

        config = new PayoneConfigurationModel();
        config.setMerchantId(MERCHANT_ID);
    }

    @After
    public void tearDown() throws Exception {
        mocks.close();
    }

    @Test
    public void shouldCreateCommerceCase() throws Exception {
        final CreateCommerceCaseRequest request = new CreateCommerceCaseRequest();
        final CreateCommerceCaseResponse expected = new CreateCommerceCaseResponse();

        when(apiClient.createCommerceCaseRequest(MERCHANT_ID, request)).thenReturn(expected);

        final CreateCommerceCaseResponse result = service.createCommerceCase(config, request);

        assertNotNull(result);
        verify(apiClient).createCommerceCaseRequest(MERCHANT_ID, request);
    }

    @Test
    public void shouldGetCommerceCase() throws Exception {
        final String commerceCaseId = "case-123";
        final CommerceCaseResponse expected = new CommerceCaseResponse();

        when(apiClient.getCommerceCaseRequest(MERCHANT_ID, commerceCaseId)).thenReturn(expected);

        final CommerceCaseResponse result = service.getCommerceCase(config, commerceCaseId);

        assertNotNull(result);
        verify(apiClient).getCommerceCaseRequest(MERCHANT_ID, commerceCaseId);
    }

    @Test
    public void shouldRejectNullConfig() {
        assertThrows(NullPointerException.class, () -> service.createCommerceCase(null, new CreateCommerceCaseRequest()));
        assertThrows(NullPointerException.class, () -> service.getCommerceCase(null, "case-1"));
    }

    @Test
    public void shouldRejectNullRequest() {
        assertThrows(NullPointerException.class, () -> service.createCommerceCase(config, null));
    }

    @Test
    public void shouldRejectNullCommerceCaseId() {
        assertThrows(NullPointerException.class, () -> service.getCommerceCase(config, null));
    }

    @Test
    public void shouldWrapSdkException() throws Exception {
        when(apiClient.createCommerceCaseRequest(any(), any())).thenThrow(new RuntimeException("API timeout"));

        assertThrows(IllegalStateException.class, () -> service.createCommerceCase(config, new CreateCommerceCaseRequest()));
    }

    /**
     * ApiErrorResponseException.getMessage() is null when PCP's error body carries no
     * message string; the real detail is in getErrors(). wrapSdkError must surface that
     * via PayoneApiErrorMapper.describe instead of the bare null.
     */
    @Test
    public void shouldSurfaceApiErrorDetail_notNullMessage() throws Exception {
        final com.payone.commerce.platform.lib.models.APIError apiError =
                new com.payone.commerce.platform.lib.models.APIError()
                        .errorCode("PAYMENT_METHOD_NOT_SUPPORTED")
                        .category("PAYMENT_PLATFORM_ERROR")
                        .httpStatusCode(502);

        when(apiClient.createCommerceCaseRequest(any(), any())).thenThrow(
                new com.payone.commerce.platform.lib.errors.ApiErrorResponseException(
                        502, null, java.util.List.of(apiError)));

        final IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.createCommerceCase(config, new CreateCommerceCaseRequest()));

        org.junit.Assert.assertNotEquals(
                "PCP createCommerceCase failed for merchantId [" + MERCHANT_ID + "]: null",
                thrown.getMessage());
        org.junit.Assert.assertTrue(thrown.getMessage().contains("PAYMENT_METHOD_NOT_SUPPORTED"));
    }
}
