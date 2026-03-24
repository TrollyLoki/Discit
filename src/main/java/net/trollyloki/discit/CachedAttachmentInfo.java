package net.trollyloki.discit;

import org.jspecify.annotations.NullMarked;

@NullMarked
public record CachedAttachmentInfo(
        String url,
        String fileName
) {
}
