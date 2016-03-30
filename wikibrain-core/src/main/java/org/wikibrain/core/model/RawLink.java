package org.wikibrain.core.model;

import org.wikibrain.core.lang.Language;

/**
 * Created by Josh on 3/29/16.
 * Used by the updated Raw Page to contain information about all the contained links
 */
public class RawLink {
    private final Language language;
    private final int sourceId;
    private final String anchorText;
    private final int position;
    private final String target;
    private final RawLinkType type;

    public enum RawLinkType {EXTERNAL, INTERNAL, AUDIO, VIDEO, IMAGE, UNKNOWN};

    public RawLink(Language language, int sourceId, String anchorText, int position, String target, RawLinkType type) {
        this.language = language;
        this.sourceId = sourceId;
        this.anchorText = anchorText;
        this.position = position;
        this.target = target;
        this.type = type;
    }

    public Language getLanguage() {
        return language;
    }

    public String getAnchorText() {
        return anchorText;
    }

    public int getSourceId(){
        return sourceId;
    }

    // Position inside of RawPage's WikiText
    public int getPosition() {
        return position;
    }

    public RawLinkType getType() {
        return type;
    }

    public String getTarget() {
        return target;
    }

    @Override
    public String toString() {
        return "RawLink{" +
                "language=" + language +
                ", anchorText='" + anchorText + '\'' +
                ", sourceId=" + sourceId +
                ", location=" + position +
                ", type=" + type +
                ", destination=" + target +
                '}';
    }
}
