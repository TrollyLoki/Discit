package net.trollyloki.discit;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.utils.NamedAttachmentProxy;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@NullMarked
public class AttachmentCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(AttachmentCache.class);

    private static final long EXPIRATION_TIME = Duration.ofMinutes(60).toNanos();

    private record AttachmentInfo(String url, String fileName) {
        AttachmentInfo(Message.Attachment attachment) {
            this(attachment.getUrl(), attachment.getFileName());
        }

        NamedAttachmentProxy getProxy() {
            return new NamedAttachmentProxy(url, fileName);
        }
    }

    private record CachedAttachments(long nanoTime, List<AttachmentInfo> attachmentInfo) {
        CachedAttachments(List<Message.Attachment> attachments) {
            this(System.nanoTime(), attachments.stream().map(AttachmentInfo::new).toList());
        }

        boolean isExpired() {
            return (System.nanoTime() - nanoTime) > EXPIRATION_TIME;
        }
    }

    private final Map<Long, CachedAttachments> attachmentsForUser = new HashMap<>();

    public void removeExpired() {
        attachmentsForUser.entrySet().removeIf(entry -> {
            if (entry.getValue().isExpired()) {
                LOGGER.info("Cached attachments for user {} expired", entry.getKey());
                return true;
            }
            return false;
        });
    }

    public void put(User user, List<Message.Attachment> attachments) {
        // Remove any expired values before adding more
        removeExpired();

        attachmentsForUser.put(user.getIdLong(), new CachedAttachments(attachments));
        LOGGER.info("Attachment cache contains {} entries after put", attachmentsForUser.size());
    }

    public @Nullable NamedAttachmentProxy pop(User user, int index) {
        CachedAttachments attachments = attachmentsForUser.remove(user.getIdLong());
        if (attachments == null) return null;

        LOGGER.info("Attachment cache contains {} entries after pop", attachmentsForUser.size());
        return attachments.attachmentInfo.get(index).getProxy();
    }

}
