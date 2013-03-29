/*
 * Copyright (c) Mirth Corporation. All rights reserved.
 * http://www.mirthcorp.com
 * 
 * The software in this package is published under the terms of the MPL
 * license a copy of which has been included with this distribution in
 * the LICENSE.txt file.
 */

package com.mirth.connect.plugins.messagepruner;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.session.ResultContext;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.SqlSession;
import org.apache.log4j.Logger;

import com.mirth.connect.donkey.model.message.ConnectorMessage;
import com.mirth.connect.donkey.model.message.Message;
import com.mirth.connect.donkey.model.message.Status;
import com.mirth.connect.donkey.server.Donkey;
import com.mirth.connect.donkey.server.controllers.ChannelController;
import com.mirth.connect.donkey.server.data.DonkeyDao;
import com.mirth.connect.donkey.server.data.DonkeyDaoFactory;
import com.mirth.connect.server.controllers.MessagePrunerException;
import com.mirth.connect.server.util.DatabaseUtil;
import com.mirth.connect.server.util.SqlConfig;

public class DefaultMessagePruner implements MessagePruner {
    private List<Status> skipStatuses = new ArrayList<Status>();
    private boolean skipIncomplete = true;
    private int retryCount = 0;
    private MessageArchiver messageArchiver;
    private Logger logger = Logger.getLogger(this.getClass());

    public List<Status> getSkipStatuses() {
        return skipStatuses;
    }

    public void setSkipStatuses(List<Status> skipStatuses) {
        this.skipStatuses = skipStatuses;
    }

    public boolean isSkipIncomplete() {
        return skipIncomplete;
    }

    public void setSkipIncomplete(boolean skipIncomplete) {
        this.skipIncomplete = skipIncomplete;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public MessageArchiver getMessageArchiver() {
        return messageArchiver;
    }

    public void setMessageArchiver(MessageArchiver archiver) {
        this.messageArchiver = archiver;
    }

    @Override
    public int[] executePruner(String channelId, Calendar messageDateThreshold, Calendar contentDateThreshold) throws MessagePrunerException {
        if (messageDateThreshold == null && contentDateThreshold == null) {
            return new int[] { 0, 0 };
        }

        if (messageDateThreshold != null && contentDateThreshold != null && contentDateThreshold.getTimeInMillis() <= messageDateThreshold.getTimeInMillis()) {
            contentDateThreshold = null;
        }

        int tryNum = 1;
        int numContentPruned = 0;
        int numMessagesPruned = 0;
        boolean retry;

        Map<String, Object> params = new HashMap<String, Object>();
        params.put("localChannelId", ChannelController.getInstance().getLocalChannelId(channelId));
        params.put("skipIncomplete", skipIncomplete);

        if (!skipStatuses.isEmpty()) {
            params.put("skipStatuses", skipStatuses);
        }

        do {
            SqlSession session = SqlConfig.getSqlSessionManager().openSession();
            retry = false;

            try {
                if (messageArchiver != null) {
                    logger.debug("Archiving messages");
                    params.put("dateThreshold", (contentDateThreshold != null) ? contentDateThreshold : messageDateThreshold);
                    session.select("Message.prunerSelectMessagesToArchive", params, new ArchiverResultHandler(Donkey.getInstance().getDaoFactory(), channelId));
                }

                // if either delete query fails, it is possible that some messages will have been archived, but not yet pruned
                if (contentDateThreshold != null) {
                    logger.debug("Pruning content");
                    params.put("dateThreshold", contentDateThreshold);
                    numContentPruned += session.delete("Message.prunerDeleteMessageContent", params);
                }

                if (messageDateThreshold != null) {
                    logger.debug("Pruning messages");
                    params.put("dateThreshold", messageDateThreshold);

                    numContentPruned += session.delete("Message.prunerDeleteMessageContent", params);

                    if (DatabaseUtil.statementExists("Message.prunerDeleteCustomMetadata")) {
                        session.delete("Message.prunerDeleteCustomMetadata", params);
                    }

                    if (DatabaseUtil.statementExists("Message.prunerDeleteAttachments")) {
                        session.delete("Message.prunerDeleteAttachments", params);
                    }

                    if (DatabaseUtil.statementExists("Message.prunerDeleteConnectorMessages")) {
                        session.delete("Message.prunerDeleteConnectorMessages", params);
                    }

                    numMessagesPruned += session.delete("Message.prunerDeleteMessages", params);
                }

                logger.debug("Committing");
                session.commit();
            } catch (Exception e) {
                retry = true;

                if (tryNum > retryCount) {
                    throw new MessagePrunerException("Failed to prune messages", e);
                } else {
                    tryNum++;
                }
            } finally {
                session.close();
            }
        } while (retry);

        return new int[] { numMessagesPruned, numContentPruned };
    }

    private class ArchiverResultHandler implements ResultHandler {
        private DonkeyDaoFactory daoFactory;
        private String channelId;

        public ArchiverResultHandler(DonkeyDaoFactory daoFactory, String channelId) {
            this.daoFactory = daoFactory;
            this.channelId = channelId;
        }

        @Override
        public void handleResult(ResultContext context) {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) context.getResultObject();

            Long messageId = (Long) result.get("id");

            if (!messageArchiver.isArchived(messageId)) {
                Calendar receivedDate = Calendar.getInstance();
                receivedDate.setTimeInMillis(((Timestamp) result.get("received_date")).getTime());

                Map<Integer, ConnectorMessage> connectorMessages = null;
                DonkeyDao dao = null;

                try {
                    dao = daoFactory.getDao();
                    connectorMessages = dao.getConnectorMessages(channelId, messageId);
                } finally {
                    dao.close();
                }

                Message message = new Message();
                message.setMessageId(messageId);
                message.setChannelId(channelId);
                message.setReceivedDate(receivedDate);
                message.setProcessed((Boolean) result.get("processed"));
                message.setServerId((String) result.get("server_id"));
                message.setImportId((Long) result.get("import_id"));
                message.getConnectorMessages().putAll(connectorMessages);

                messageArchiver.archiveMessage(message);
            }
        }
    }
}