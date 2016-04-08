package org.wikibrain.core.model;

import org.apache.batik.dom.svg.SVGDOMImplementation;
import org.apache.batik.transcoder.image.ImageTranscoder;
import org.apache.batik.util.SVGConstants;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wikibrain.core.lang.Language;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URL;
import java.util.Base64;
import org.apache.batik.transcoder.*;

/**
 * Created by Josh on 3/30/16.
 */
public class RawImage {
    private static final Logger LOG = LoggerFactory.getLogger(RawImage.class);
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

    // This will probably require downloading a file, so use sparingly
    public BufferedImage generateImage() throws IOException {
        int extensionIndex = imageLocation.lastIndexOf(".");
        if (extensionIndex < 0) {
            LOG.warn("Image does not have a valid extension");
            return null;
        }
        String extension = imageLocation.substring(extensionIndex + 1).toLowerCase().trim();

        // Get the input stream
        InputStream input = new URL(getImageLocation()).openStream();

        // Check if ImageIO can handle the extension
        if (ImageIO.getImageReadersBySuffix(extension).hasNext()) {
            // Get the image using ImageIO
            return ImageIO.read(input);
        } else if (extension.equals("svg")) {
            // Download and rasterize the SVG file using other means
            return rasterizeSVGFile(input);
        }

        return null;
    }

    private BufferedImage rasterizeSVGFile(String string) throws IOException {
        InputStream stream = new ByteArrayInputStream(string.getBytes("UTF-8"));
        return rasterizeSVGFile(stream);
    }

    private BufferedImage rasterizeSVGFile(InputStream stream) throws IOException {
        // Adapted from:
        // http://stackoverflow.com/questions/11435671/how-to-get-a-buffererimage-from-a-svg

        final BufferedImage[] imagePointer = new BufferedImage[1];
        String css = "svg {" +
                "shape-rendering: geometricPrecision;" +
                "text-rendering:  geometricPrecision;" +
                "color-rendering: optimizeQuality;" +
                "image-rendering: optimizeQuality;" +
                "}";
        File cssFile = File.createTempFile("wikibrain-svg-default-override-", ".css");
        FileUtils.writeStringToFile(cssFile, css);

        TranscodingHints transcoderHints = new TranscodingHints();
        transcoderHints.put(ImageTranscoder.KEY_XML_PARSER_VALIDATING, Boolean.FALSE);
        transcoderHints.put(ImageTranscoder.KEY_DOM_IMPLEMENTATION, SVGDOMImplementation.getDOMImplementation());
        transcoderHints.put(ImageTranscoder.KEY_DOCUMENT_ELEMENT_NAMESPACE_URI, SVGConstants.SVG_NAMESPACE_URI);
        transcoderHints.put(ImageTranscoder.KEY_DOCUMENT_ELEMENT, "svg");
        transcoderHints.put(ImageTranscoder.KEY_USER_STYLESHEET_URI, cssFile.toURI().toString());

        try {
            TranscoderInput input = new TranscoderInput(stream);
            ImageTranscoder transcoder = new ImageTranscoder() {
                @Override
                public BufferedImage createImage(int w, int h) {
                    return new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                }

                @Override
                public void writeImage(BufferedImage bufferedImage, TranscoderOutput transcoderOutput) throws TranscoderException {
                    imagePointer[0] = bufferedImage;
                }
            };
            transcoder.setTranscodingHints(transcoderHints);
            transcoder.transcode(input, null);
        } catch (TranscoderException e) {
            throw new IOException("Unable to rasterize svg stream");
        } finally {
            cssFile.delete();
        }

        return imagePointer[0];
    }

    // Creates a PNG Base64 String to display on a webpage
    public String generateBase64String() throws IOException {
        BufferedImage image = generateImage();
        if (image == null) {
            return null;
        }

        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        OutputStream base64Stream = Base64.getEncoder().wrap(byteStream);
        ImageIO.write(image, "png", base64Stream);
        return byteStream.toString("UTF-8");
    }

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

    @Override
    public int hashCode() {
        return language.hashCode() ^ sourceId ^ imageLocation.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof RawImage) {
            RawImage r = (RawImage)obj;
            return r.language == language && r.sourceId == sourceId && r.name == name && r.pageLocation == pageLocation
                    && r.imageLocation == imageLocation && r.caption == caption;
        }

        return super.equals(obj);
    }
}
