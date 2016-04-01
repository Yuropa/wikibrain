package org.wikibrain.core.model;

import org.wikibrain.core.lang.Language;

/**
 * Created by Josh on 3/30/16.
 */
public class RawImage {
    private final Language language;
    private final int sourceId;
    private final String name;
    private final String pageLocation;
    private final String imageLocation;
    private final String caption;

    public RawImage(Language language, int sourceId, String name, String pageLocation, String imageLocation, String caption) {
        this.language = language;
        this.sourceId = sourceId;
        this.name = name;
        this.pageLocation = pageLocation;
        this.imageLocation = imageLocation;
        this.caption = caption;
    }

    public Language getLanguage() {
        return language;
    }

    public int getSourceId(){
        return sourceId;
    }

    public String getName() { return name; }

    public String getPageLocation() { return pageLocation; }

    public String getImageLocation() { return imageLocation; }

    public String getCaption() { return caption; }

    @Override
    public String toString() {
        return "RawLink{" +
                "language=" + language +
                ", sourceId=" + sourceId +
                ", name=" + name +
                ", pageLocation=" + pageLocation +
                ", imageLocation=" + imageLocation +
                ", caption=" + caption +
                '}';
    }
}
