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

    public RawImage(Language language, int sourceId, String name, String pageLocation, String imageLocation) {
        this.language = language;
        this.sourceId = sourceId;
        this.name = name;
        this.pageLocation = pageLocation;
        this.imageLocation = imageLocation;
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

    @Override
    public String toString() {
        return "RawLink{" +
                "language=" + language +
                ", sourceId=" + sourceId +
                ", name=" + name +
                ", pageLocation=" + pageLocation +
                ", imageLocation=" + imageLocation +
                '}';
    }
}
