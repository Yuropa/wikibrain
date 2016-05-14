package org.wikibrain.webapi;

import edu.emory.mathcs.backport.java.util.Collections;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.eclipse.jetty.util.ConcurrentHashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wikibrain.conf.ConfigurationException;
import org.wikibrain.conf.Configurator;
import org.wikibrain.core.cmd.Env;
import org.wikibrain.core.dao.DaoException;
import org.wikibrain.core.dao.LocalLinkDao;
import org.wikibrain.core.dao.LocalPageDao;
import org.wikibrain.core.dao.RawImageDao;
import org.wikibrain.core.lang.Language;
import org.wikibrain.core.model.LocalLink;
import org.wikibrain.core.model.LocalPage;
import org.wikibrain.core.model.RawImage;
import org.wikibrain.sr.SRMetric;
import org.wikibrain.sr.SRResultList;
import org.wikibrain.sr.wikify.Wikifier;
import org.wikibrain.utils.ParallelForEach;
import org.wikibrain.utils.Procedure;

import java.util.*;

/**
 * Created by Josh on 4/6/16.
 * This will find images from Wikipedia from a piece of text
 */
public class CompariFactWikipediaImages implements CompariFactDataSource {
    public static final Logger LOG = LoggerFactory.getLogger(CompariFactWikipediaImages.class);
    final private RawImageDao riDao;
    final private LocalPageDao lpDao;
    final private LocalLinkDao llDoa;
    final private Map<String, SRMetric> srMetrics;
    final private Wikifier wikifier;

    CompariFactWikipediaImages(Env env) throws ConfigurationException {
        Configurator conf = env.getConfigurator();
        riDao = conf.get(RawImageDao.class);
        lpDao = conf.get(LocalPageDao.class);
        llDoa = conf.get(LocalLinkDao.class);

        srMetrics = new HashMap<String, SRMetric>();
        Language lang = env.getDefaultLanguage();

        srMetrics.put("esa", conf.get(SRMetric.class, "ESA", "language", lang.getLangCode()));
        srMetrics.put("ensemble", conf.get(SRMetric.class, "ensemble", "language", lang.getLangCode()));
        wikifier = conf.get(Wikifier.class, "websail", "language", lang.getLangCode());
    }

    // Gets all the images from a particular Wikipedia page
    private List<InternalImage> createImagesFromId(ScoredLink link) throws DaoException {
        List<InternalImage> images = new ArrayList<InternalImage>();

        // Resolve the id to a local page
        LocalPage lp = lpDao.getById(link.lang, link.localId);

        // Add the images on the local page to the images array
        for (RawImage image : riDao.getImages(link.lang, link.localId)) {
            InternalImage internalImage = new InternalImage(image.getName(), image.getImageLocation(),
                    image.getCaption(), image.isPhotograph(),
                    image.getWidth(), image.getHeight(), link.method, link.score, lp.getTitle().getCanonicalTitle());

            internalImage.addDebugData("debug", link.debugText);
            internalImage.addDebugData("link-anchor-text", link.anchorText);
            internalImage.addDebugData("esa-wikify-anchor-text", link.esaWikifiyAnchorText);
            images.add(internalImage);
        }

        System.out.println("Found page " + lp.getTitle().getCanonicalTitle() + " with " + images.size() + " images");

        return images;
    }

    // Finds the links from a piece of text using a particular SR metric's most similar
    // method can be "esa" or "ensemble", but you should probably just use ESA
    private List<ScoredLink> srImages(String text, int count, final String method) throws DaoException {
        final List<ScoredLink> result = new ArrayList<ScoredLink>();

        if (count <= 0) {
            // No need to search
            return result;
        }

        // Get the particular SR metric (we store them in a dictionary to cache them)
        final SRMetric sr = srMetrics.get(method);
        // Perform most similar search
        final SRResultList mostSimilar = sr.mostSimilar(text, count);

        // Convert the SRResultList to ScoredLinks
        for (int i = 0; i < mostSimilar.numDocs(); i++) {
            int id = mostSimilar.getId(i);
            double score = mostSimilar.getScoreForId(id);

            ScoredLink link = new ScoredLink(sr.getLanguage(), id, score, method);
            link.debugText = method + " (" + score + ")";
            result.add(link);
        }

        return result;
    }

    // A simple class used internally to hold candidate links with an associated score
    // This allows us to sort the links as needed
    private class ScoredLink {
        ScoredLink(Language lang, int localId, double score, String method) {
            this.lang = lang;
            this.localId = localId;
            this.score = score;
            this.method = method;
        }

        public Language lang;
        public int localId;
        public double score;
        public String method;
        public String anchorText = "";
        public String debugText = "";
        public String esaWikifiyAnchorText = "";

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof ScoredLink) {
                ScoredLink l = (ScoredLink)obj;
                return l.lang.equals(lang) && l.localId == localId;
            }

            return super.equals(obj);
        }

        @Override
        public int hashCode() {
            return this.lang.hashCode() ^ (this.localId * 31);
        }
    }

    // Performs wikification on a piece of text. This results a list of links
    // with scores calcaulted by summing the position of the entities in the text
    // Links which appear at the beginning have a higher score than those which appear at the end
    private List<ScoredLink> wikifyText(String text) throws DaoException{
        Map<ScoredLink, Double> values = new HashMap<ScoredLink, Double>();
        Map<ScoredLink, Integer> counts = new HashMap<ScoredLink, Integer>();

        // Run the wikifier to get a set of local links (probably with duplicates)
        for (LocalLink ll : wikifier.wikify(text)) {
            // Calculate the "score" of this link based on location in the text
            Double value = 1.0 - (double) ll.getLocation() / (double) text.length();
            // The number of times this link appears
            int count = 1;

            // Construct a new scored link
            ScoredLink scoredLink = new ScoredLink(ll.getLanguage(), ll.getLocalId(), 0.0, "wikify");
            scoredLink.anchorText = ll.getAnchorText();

            // If the link was already found, we will want to add out current score to the old score
            if (values.containsKey(scoredLink)) {
                value += values.get(scoredLink);
            }

            // If the link was already found, we will want to add in the previous number of times the link was seen
            if (counts.containsKey(scoredLink)) {
                count += counts.get(scoredLink);
            }

            // Store all the data in maps
            values.put(scoredLink, value);
            counts.put(scoredLink, count);
        }

        // Get the total score, this will be used to normalize the score for different pieces of text
        double totalScore = 0.0;
        for (double d : values.values()) {
            totalScore += d;
        }

        // Add debugging information and normalize the scores
        List<ScoredLink> result = new ArrayList<ScoredLink>();
        for (ScoredLink link : values.keySet()) {
            /*
            if (counts.get(link) <= 1 && values.get(link) < 0.3) {
                // Links should be mentioned more than once
                // And they should have a resonable score
                continue;
            }
            */

            link.score = values.get(link) / totalScore;
            link.debugText = "=> " + link.anchorText + " " + "wiki(" + counts.get(link) + ", " + link.score + ")";
            result.add(link);
        }
        return result;
    }

    // Specifies a default value for the number of sr links in getLinksForMethod()
    public List<ScoredLink> getLinksForMethod(String text, String method) throws DaoException {
        int MAX_SR_LINKS = 30;
        return getLinksForMethod(text, method, MAX_SR_LINKS);
    }

    // Extracts a list of links from a piece of text using the speicified method
    // The srLinksCount is only used in the situation when using an SR most similar search
    public List<ScoredLink> getLinksForMethod(String text, final String method, final int srLinksCount) throws DaoException {
        final Set<ScoredLink> foundLinks = new ConcurrentHashSet<ScoredLink>();

        if (method.equals("esa") || method.equals("ensemble")) {
            // This is a vanilla SR search
            // Get all the images using the SR searching wrapper
            foundLinks.addAll(srImages(text, srLinksCount, method));
        } else if (method.startsWith("wikify")) {
            // Determine if there is a second-order search (like esa) store it in srMethod
            int index = method.lastIndexOf("-");
            final String srMethod;
            if (index >= 0) {
                srMethod = method.substring(index + 1);
            } else {
                srMethod = null;
            }

            // Wikifiy the text
            foundLinks.addAll(wikifyText(text));

            if (srMethod != null) {
                // We should perform a secondary search on all the found links (we do it in parallel to help make it faster)
                ParallelForEach.loop(new ArrayList<ScoredLink>(foundLinks), new Procedure<ScoredLink>() {
                    @Override
                    public void call(ScoredLink link) throws Exception {
                        // Find the particular page we found a link fof
                        LocalPage page = lpDao.getById(link.lang, link.localId);
                        int numberOfImages = (int) (srLinksCount * link.score);

                        System.out.println("Getting " + numberOfImages + " esa image for term " + page.getTitle().getCanonicalTitle());

                        // Perform a most similar search on the page title (this should find similar topics to the link)
                        for (ScoredLink l : getLinksForMethod(page.getTitle().getCanonicalTitle(), srMethod, numberOfImages)) {
                            l.debugText = srMethod + " from (" + link.debugText + ") " + l.debugText;
                            l.esaWikifiyAnchorText = page.getTitle().getCanonicalTitle();
                            l.method = method;
                            foundLinks.add(l);
                        }
                    }
                });
            }
        } else if (method.equals("all")) {
            // This is a combination os ESA and Wikify-ESA
            foundLinks.addAll(getLinksForMethod(text, "esa"));
            foundLinks.addAll(getLinksForMethod(text, "wikify-esa"));
        }

        return new ArrayList<ScoredLink>(foundLinks);
    }

    // Generates a list of images from a peice of text (there might be duplicate images)
    // The method can be:
    //  - esa
    //  - ensemble
    //  - wikify
    //  - wikify-esa (in addition to wikfication performs ESA on the found pages)
    //  - wikify-ensemble (in addition to wikfication performs ESA on the found pages)
    //  - all (alias for both esa and wikify-esa)
    public List<InternalImage> generateimages(final String text, final String method) throws DaoException {
        final List<InternalImage> result = Collections.synchronizedList(new ArrayList<InternalImage>());

        System.out.println("Generating Wikipedia Images");
        System.out.println("Using method " + method);

        // Search for all the links from the text
        List<ScoredLink> links = getLinksForMethod(text, method);

        // Print out the pages and remove any null links
        System.out.println("Found possible pages:");
        for (ScoredLink l : new ArrayList<ScoredLink>(links)) {
            LocalPage lp = lpDao.getById(l.lang, l.localId);

            if (lp == null) {
                links.remove(l);
            } else  {
                System.out.println("\t" + lp.getTitle().getCanonicalTitle() + " : " + l.debugText);
            }
        }

        // Get ESA metric
        final SRMetric srMetric = srMetrics.get("esa");

        // We will perform SR with the article title to try to remove extraneous titles
        ParallelForEach.loop(links, new Procedure<ScoredLink>() {
            @Override
            public void call(ScoredLink link) throws Exception {
                try {
                    // Resolve the link to a local page
                    LocalPage lp = lpDao.getById(link.lang, link.localId);

                    // Server hangs if we hit these pages...
                    if (lp.getTitle().getCanonicalTitle().toLowerCase().trim().startsWith("united states presidential election")) {
                        return;
                    }

                    // Get the similarity between the page and the original text
                    double score = srMetric.similarity(lp.getTitle().getCanonicalTitle(), text, false).getScore();

                    System.out.println("Page title score " + score + " : " + lp.getTitle().getCanonicalTitle());

                    // Make sure the similiarity is high enough
                    if (score < 0.8) {
                        return;
                    }

                    // Get all the images on the found Wikipeida pages
                    for (InternalImage image : createImagesFromId(link)) {
                        if (method.startsWith("wikify")) {
                            image.addDebugData("wikify resolve", link.anchorText);
                        }
                        result.add(image);
                    }
                } catch (Exception e) {
                    System.out.println("\n\n\nBegin Error");
                    e.printStackTrace();
                    LOG.info(e.getLocalizedMessage());
                    LOG.info(ExceptionUtils.getFullStackTrace(e));
                    System.out.println("End Error\n\n");
                }
            }
        });

        System.out.println("Generated " + result.size() + " Wikipedia Images");

        return result;
    }
}