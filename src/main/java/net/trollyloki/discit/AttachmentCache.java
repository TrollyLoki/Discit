package net.trollyloki.discit;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.utils.NamedAttachmentProxy;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.List;

@NullMarked
public class AttachmentCache {

    private record AttachmentInfo(String url, String fileName) {
        AttachmentInfo(Message.Attachment attachment) {
            this(attachment.getUrl(), attachment.getFileName());
        }

        NamedAttachmentProxy getProxy() {
            return new NamedAttachmentProxy(url, fileName);
        }
    }

    private final InteractionDataCache<Long, List<AttachmentInfo>> attachmentsForUser = new InteractionDataCache<>();

    public void put(User user, List<Message.Attachment> attachments) {
        attachmentsForUser.put(user.getIdLong(), attachments.stream().map(AttachmentInfo::new).toList());
    }

    public @Nullable NamedAttachmentProxy pop(User user, int index) {
        List<AttachmentInfo> attachments = attachmentsForUser.pop(user.getIdLong());
        if (attachments == null) return null;

        return attachments.get(index).getProxy();
    }

}
