package com.payone.pcp.webhook.handler.impl;

import com.payone.pcp.core.model.PayoneCommerceCaseModel;
import com.payone.pcp.webhook.dao.PayoneWebhookEventDao;
import com.payone.pcp.webhook.dto.PayoneWebhookCommerceCaseDto;
import com.payone.pcp.webhook.dto.PayoneWebhookEventDto;
import com.payone.pcp.webhook.enums.PayoneWebhookDomain;
import com.payone.pcp.webhook.enums.PayoneWebhookEventType;
import com.payone.pcp.webhook.handler.PayoneWebhookEventHandler;
import com.payone.pcp.webhook.model.PayoneWebhookEventModel;
import com.payone.pcp.webhook.service.data.PayoneWebhookTarget;

import de.hybris.platform.servicelayer.exceptions.ModelSavingException;
import de.hybris.platform.servicelayer.model.ModelService;

import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps the local {@code PayoneCommerceCase} in step with a {@code commerce_case.*}
 * event: creates it when the case is not known here yet (first sighting of a case
 * created directly on PCP), and converges its merchant reference when it is.
 */
public class PayoneCommerceCaseWebhookHandler implements PayoneWebhookEventHandler {
    private static final Logger LOG = LoggerFactory.getLogger(PayoneCommerceCaseWebhookHandler.class);

    private ModelService modelService;
    private PayoneWebhookEventDao payoneWebhookEventDao;

    @Override
    public boolean supports(final PayoneWebhookEventType eventType) {
        return eventType != null && eventType.getDomain() == PayoneWebhookDomain.COMMERCE_CASE;
    }

    @Override
    public void handle(final PayoneWebhookEventDto envelope, final PayoneWebhookEventType eventType,
                       final PayoneWebhookTarget target, final PayoneWebhookEventModel event) {

        if (target.hasCommerceCase()) {
            updateMerchantReference(envelope, eventType, target.getCommerceCase());
            return;
        }

        final PayoneWebhookCommerceCaseDto dto = envelope.getCommerceCase();
        if (dto == null || StringUtils.isBlank(dto.getId())) {
            LOG.warn("[PAYONE] Webhook eventId={} type={} carries no commerceCase id; cannot create local commerce case",
                    envelope.getId(), eventType.getCode());
            return;
        }

        final PayoneCommerceCaseModel commerceCase = createOrAdopt(dto);

        event.setCommerceCase(commerceCase);
        getModelService().save(event);

        LOG.info("[PAYONE] Webhook eventId={} type={}: linked local commerce case {} (merchantReference={})",
                envelope.getId(), eventType.getCode(), commerceCase.getCommerceCaseId(), commerceCase.getMerchantReference());
    }

    /**
     * Looks up the local case by commerceCaseId first, and only creates it when
     * no such row exists yet.
     */
    private PayoneCommerceCaseModel createOrAdopt(final PayoneWebhookCommerceCaseDto dto) {
        final Optional<PayoneCommerceCaseModel> existing =
                getPayoneWebhookEventDao().findCommerceCaseById(dto.getId());
        if (existing.isPresent()) {
            return existing.get();
        }

        final PayoneCommerceCaseModel commerceCase = getModelService().create(PayoneCommerceCaseModel.class);
        commerceCase.setCommerceCaseId(dto.getId());
        commerceCase.setMerchantReference(dto.getMerchantReference());
        getModelService().save(commerceCase);
        return commerceCase;
    }

    /**
     * Converges the case's merchant reference on the snapshot. Writes nothing when
     * it already matches, so a replay is a no-op; a snapshot that carries no
     * reference is treated as "not reported", never as "cleared".
     */
    private void updateMerchantReference(final PayoneWebhookEventDto envelope, final PayoneWebhookEventType eventType,
                                         final PayoneCommerceCaseModel commerceCase) {
        final PayoneWebhookCommerceCaseDto dto = envelope.getCommerceCase();
        if (dto == null || StringUtils.isBlank(dto.getMerchantReference())
                || StringUtils.equals(dto.getMerchantReference(), commerceCase.getMerchantReference())) {
            return;
        }

        LOG.info("[PAYONE] Webhook eventId={} type={}: commerce case {} merchantReference {} -> {}",
                envelope.getId(), eventType.getCode(), commerceCase.getCommerceCaseId(),
                commerceCase.getMerchantReference(), dto.getMerchantReference());

        commerceCase.setMerchantReference(dto.getMerchantReference());
        getModelService().save(commerceCase);
    }

    public ModelService getModelService() {
        return modelService;
    }

    public void setModelService(final ModelService modelService) {
        this.modelService = modelService;
    }

    public PayoneWebhookEventDao getPayoneWebhookEventDao() {
        return payoneWebhookEventDao;
    }

    public void setPayoneWebhookEventDao(final PayoneWebhookEventDao payoneWebhookEventDao) {
        this.payoneWebhookEventDao = payoneWebhookEventDao;
    }
}
