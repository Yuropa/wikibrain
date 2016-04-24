package org.wikibrain.webapi;

import cern.jet.random.StudentT;
import com.mchange.v2.lang.ThreadUtils;
import edu.emory.mathcs.backport.java.util.Collections;
import net.sf.cglib.core.Local;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.eclipse.jetty.util.ConcurrentHashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wikibrain.conf.Configuration;
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
import org.wikibrain.sr.SRResult;
import org.wikibrain.sr.SRResultList;
import org.wikibrain.sr.wikify.Wikifier;
import org.wikibrain.utils.ParallelForEach;
import org.wikibrain.utils.Procedure;
import org.wikibrain.utils.WpThreadUtils;

import java.util.*;
import java.util.function.ToDoubleFunction;
import java.util.stream.DoubleStream;

/**
 * Created by Josh on 4/6/16.
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

    private List<InternalImage> createImageFromId(Language lang, int localId, String method, double score) throws DaoException {
        List<InternalImage> images = new ArrayList<InternalImage>();

        LocalPage lp = lpDao.getById(lang, localId);

        for (RawImage image : riDao.getImages(lang, localId)) {
            images.add(new InternalImage(image.getLanguage(), image.getSourceId(), image.getName(),
                    image.getPageLocation(), image.getImageLocation(), image.getCaption(), image.isPhotograph(),
                    image.getWidth(), image.getHeight(), method, score, lp.getTitle().getCanonicalTitle()));
        }

        System.out.println("Found page " + lp.getTitle().getCanonicalTitle() + " with " + images.size() + " images");

        return images;
    }

    private List<ScoredLink> srImages(String text, int count, final String method) throws DaoException {
        final List<ScoredLink> result = new ArrayList<ScoredLink>();

        if (count <= 0) {
            // No need to search
            return result;
        }

        final SRMetric sr = srMetrics.get(method);
        final SRResultList mostSimilar = sr.mostSimilar(text, count);

        for (int i = 0; i < mostSimilar.numDocs(); i++) {
            int id = mostSimilar.getId(i);
            double score = mostSimilar.getScoreForId(id);

            ScoredLink link = new ScoredLink(sr.getLanguage(), id, score);
            link.debugText = method + " (" + score + ")";
            result.add(link);
        }

        return result;
    }

    private class ScoredLink {
        ScoredLink(Language lang, int localId, double score) {
            this.lang = lang;
            this.localId = localId;
            this.score = score;
        }

        public Language lang;
        public int localId;
        public double score;
        public String anchorText = "";
        public String debugText = "";

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

    private List<ScoredLink> wikifyText(String text) throws DaoException{
        Map<ScoredLink, Double> values = new HashMap<ScoredLink, Double>();
        Map<ScoredLink, Integer> counts = new HashMap<ScoredLink, Integer>();
        for (LocalLink ll : wikifier.wikify(text)) {
            Double value = 1.0 - (double) ll.getLocation() / (double) text.length();
            int count = 1;

            ScoredLink scoredLink = new ScoredLink(ll.getLanguage(), ll.getLocalId(), 0.0);
            scoredLink.anchorText = ll.getAnchorText();

            if (values.containsKey(scoredLink)) {
                value += values.get(scoredLink);
            }

            if (counts.containsKey(scoredLink)) {
                count += counts.get(scoredLink);
            }


            values.put(scoredLink, value);
            counts.put(scoredLink, count);
        }

        double totalScore = 0.0;
        for (double d : values.values()) {
            totalScore += d;
        }

        List<ScoredLink> result = new ArrayList<ScoredLink>();
        for (ScoredLink link : values.keySet()) {
            if (counts.get(link) <= 1 && values.get(link) < 0.3) {
                // Link should be mentioned more than once
                continue;
            }

            link.score = values.get(link) / totalScore;
            link.debugText = "=> " + link.anchorText + " " + "wiki(" + counts.get(link) + ", " + link.score + ")";
            result.add(link);
        }
        return result;
    }

    public List<ScoredLink> getLinksForMethod(String text, String method) throws DaoException {
        int MAX_SR_LINKS = 30;
        return getLinksForMethod(text, method, MAX_SR_LINKS);
    }

    public List<ScoredLink> getLinksForMethod(String text, final String method, final int srLinksCount) throws DaoException {
        final Set<ScoredLink> foundLinks = new ConcurrentHashSet<ScoredLink>();

        if (method.equals("esa") || method.equals("ensemble")) {
            if (srLinksCount == 0) {
                return java.util.Collections.emptyList();
            }

            foundLinks.addAll(srImages(text, srLinksCount, method));
        } else if (method.startsWith("wikify")) {
            int index = method.lastIndexOf("-");
            final String srMethod;
            if (index >= 0) {
                srMethod = method.substring(index + 1);
            } else {
                srMethod = null;
            }

            foundLinks.addAll(wikifyText(text));

            if (srMethod != null) {
                ParallelForEach.loop(new ArrayList<ScoredLink>(foundLinks), new Procedure<ScoredLink>() {
                    @Override
                    public void call(ScoredLink link) throws Exception {
                        LocalPage page = lpDao.getById(link.lang, link.localId);
                        int numberOfImages = (int) (srLinksCount * link.score);

                        System.out.println("Getting " + numberOfImages + " esa image for term " + page.getTitle().getCanonicalTitle());
                        for (ScoredLink l : getLinksForMethod(page.getTitle().getCanonicalTitle(), srMethod, numberOfImages)) {
                            l.debugText = srMethod + " from (" + link.debugText + ") " + l.debugText;
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

    public List<InternalImage> generateimages(String text, final String method) throws DaoException {
        final List<InternalImage> result = Collections.synchronizedList(new ArrayList<InternalImage>());

        System.out.println("Generating Wikipedia Images");
        System.out.println("Using method " + method);

        List<ScoredLink> links = getLinksForMethod(text, method);
        System.out.println("Found possible pages:");
        for (ScoredLink l : links) {
            LocalPage lp = lpDao.getById(l.lang, l.localId);
            System.out.println("\t" + lp.getTitle().getCanonicalTitle() + " : " + l.debugText);
        }

        ParallelForEach.loop(links, new Procedure<ScoredLink>() {
            @Override
            public void call(ScoredLink link) throws Exception {
                try {
                    for (InternalImage image : createImageFromId(link.lang, link.localId, method, link.score)) {
                        if (method.startsWith("wikify")) {
                            image.addDebugData("wikify resolve", link.anchorText);
                        }
                        result.add(image);
                    }
                } catch (Exception e) {
                    System.out.println("\n\n\nBegin Error");
                    e.printStackTrace();
                    LOG.error(e.getLocalizedMessage());
                    LOG.error(ExceptionUtils.getFullStackTrace(e));
                    System.out.println("End Error\n\n");
                }
            }
        });

        System.out.println("Generated " + result.size() + " Wikipedia Images");

        return result;
    }
}