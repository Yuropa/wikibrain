package org.wikibrain.webapi;

import org.apache.commons.cli.*;
import org.apache.commons.codec.language.bm.Lang;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.jooq.util.derby.sys.Sys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wikibrain.conf.ConfigurationException;
import org.wikibrain.conf.DefaultOptionBuilder;
import org.wikibrain.core.cmd.Env;
import org.wikibrain.core.cmd.EnvBuilder;
import org.wikibrain.core.dao.*;
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

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLDecoder;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author Shilad Sen
 */
public class CompariFactServer extends AbstractHandler {
    private static final Logger LOG = LoggerFactory.getLogger(CompariFactServer.class);
    private final Env env;
    private WebEntityParser entityParser;
    private List<CompariFactDataSource> sources;

    public CompariFactServer(Env env) throws ConfigurationException, DaoException {
        this.env = env;
        this.entityParser = new WebEntityParser(env);

        // Warm up necessary components
        for (Language l : env.getLanguages()) {
            LOG.info("warming up components for language: " + l);
            env.getConfigurator().get(Wikifier.class, "websail", "language", l.getLangCode());
            env.getConfigurator().get(SRMetric.class,"ESA", "language", l.getLangCode());
            env.getConfigurator().get(SRMetric.class,"simple-ensemble", "language", l.getLangCode());
        }

        this.sources = new ArrayList<CompariFactDataSource>();
        sources.add(new CompariFactWikipediaImages(env));
        sources.add(new CompariFactReferenceMap(env));
    }

    @Override
    public void handle(String target, Request request, HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse) throws IOException, ServletException {
        WikiBrainWebRequest req = new WikiBrainWebRequest(target, request, httpServletRequest, httpServletResponse);
        LOG.info("received request for {}, URL {}?{}", target, request.getRequestURL(), request.getQueryString());

        try {
            // TODO: add logging
            if (target.equals("/languages")) {
                doLanguages(req);
            } else if (target.equals("/images")) {
                doImages(req);
            } else if (target.equals("/similarity")) {
                doSimilarity(req);
            } else if (target.equals("/esa")) {
                doESA(req);
            }
        } catch (WikiBrainWebException e) {
            req.writeError(e);
        } catch (ConfigurationException e) {
            req.writeError(e);
        } catch (DaoException e) {
            req.writeError(e);
        }
    }

    private void doLanguages(WikiBrainWebRequest req) {
        List<String> langs = new ArrayList<String>();
        for (Language l : env.getLanguages()) {
            langs.add(l.getLangCode());
        }
        Collections.sort(langs);
        req.writeJsonResponse("languages", langs);
    }

    private void doImages(WikiBrainWebRequest req) throws ConfigurationException, DaoException {
        Language lang = req.getLanguage();
        String type = req.getParamOrDie("method");
        String text = URLDecoder.decode(req.getParamOrDie("text"));

        LOG.info("Generating images using method " + type + " for text: " + text);

        ArrayList<InternalImage> images = new ArrayList<InternalImage>();

        for (CompariFactDataSource source : sources) {
            images.addAll(source.generateimages(text, type));
        }

        Set<String> imageLocations = new HashSet<String>();

        // Remove duplicate images by looking at image URLs
        for (int i = 0; i < images.size(); i++) {
            RawImage image = images.get(i);

            String location = image.getImageLocation();
            if (location == null) {
                // This is a reference map
                continue;
            }

            if (imageLocations.contains(location)) {
                LOG.debug("Removing duplicate image: " + images.get(i).toString());
                images.remove(i);
                i--;
            } else {
                imageLocations.add(image.getImageLocation());
                LOG.debug("Retaining image: " + images.get(i).toString());
            }
        }

        // Sort the images based on the score (We will need to add some kind of ranking here instead)
        Collections.sort(images, Collections.reverseOrder());

        // Generate all the images
        /*
        final Map<InternalImage, String> generatedImages = new ConcurrentHashMap<InternalImage, String>();
        ParallelForEach.loop(images, WpThreadUtils.getMaxThreads() * 4, new Procedure<InternalImage>() {
            @Override
            public void call(InternalImage arg) throws Exception {
                try {
                    generatedImages.put(arg, arg.generateBase64String(400));
                } catch (Exception e) {
                    // Skip the image
                }
            }
        }, 4);
        */

        List jsonConcepts = new ArrayList();
        for (InternalImage i : images) {
            try {
                List imageURLS = new ArrayList();
                Map image = new HashMap();

                // Skip the photos
                if (i.isPhotograph()) {
                    continue;
                }

                String imageURL = i.getImageLocation();
                if (imageURL != null && imageURL.length() > 0) {
                    image.put("url", imageURL);
                } else {
                    if (i instanceof CompariFactReferenceMap.ReferenceImage && !((CompariFactReferenceMap.ReferenceImage)i).hasImage) {
                        image.put("refMap", ((CompariFactReferenceMap.ReferenceImage)i).mapJSON);
                    } else {
                        image.put("data", i.generateBase64String());
                    }
                }
                // image.put("data", generatedImages.get(i));

                image.put("caption", i.getCaption());
                image.put("debug", i.generateDebugString());
                imageURLS.add(image);

                Map obj = new HashMap();
                obj.put("title", i.getTitle());
                obj.put("images", imageURLS);
                jsonConcepts.add(obj);
            } catch (Exception e) {
                // Unable to generate image file, so we'll skip it
                continue;
            }
        }
        req.writeJsonResponse("text", text, "articles", jsonConcepts);
    }

    private SRMetric getSr(Language lang) throws ConfigurationException {
        return env.getConfigurator().get(SRMetric.class, "simple-ensemble", "language", lang.getLangCode());
    }

    private void doSimilarity(WikiBrainWebRequest req) throws ConfigurationException, DaoException {
        // TODO: support explanations
        Language lang = req.getLanguage();
        List<WebEntity> entities = entityParser.extractEntityList(req);
        if (entities.size() != 2) {
            throw new WikiBrainWebException("Similarity requires exactly two entities");
        }
        WebEntity entity1 = entities.get(0);
        WebEntity entity2 = entities.get(1);
        SRMetric sr = getSr(lang);
        SRResult r = null;
        switch (entity1.getType()) {
            case ARTICLE_ID: case TITLE:
                r = sr.similarity(entity1.getArticleId(), entity2.getArticleId(), false);
                break;
            case PHRASE:
                r = sr.similarity(entity1.getPhrase(), entity2.getPhrase(), false);
                break;
            default:
                throw new WikiBrainWebException("Unsupported entity type: " + entity1.getType());
        }
        Double sim = (r != null && r.isValid()) ? r.getScore() : null;
        req.writeJsonResponse("score", sim, "entity1", entity1.toJson(), "entity2", entity2.toJson());
    }

    private SRMetric getESA(Language lang) throws ConfigurationException {
        return env.getConfigurator().get(SRMetric.class, "ESA", "language", lang.getLangCode());
    }

    private void doESA(WikiBrainWebRequest req) throws ConfigurationException, DaoException {
        // TODO: support explanations
        try {
            System.out.println("\t1");
            Language lang = req.getLanguage();
            List<WebEntity> entities = entityParser.extractEntityList(req);
            if (entities.size() != 2) {
                throw new WikiBrainWebException("Similarity requires exactly two entities");
            }
            System.out.println("\t2");
            WebEntity entity1 = entities.get(0);
            WebEntity entity2 = entities.get(1);
            System.out.println("\t3");
            SRMetric sr = getESA(lang);
            System.out.println("\t4");
            SRResult r = null;
            switch (entity1.getType()) {
                case ARTICLE_ID:
                case TITLE:
                    System.out.println("\t5");
                    r = sr.similarity(entity1.getArticleId(), entity2.getArticleId(), false);
                    break;
                case PHRASE:
                    System.out.println("\t6");
                    r = sr.similarity(entity1.getPhrase(), entity2.getPhrase(), false);
                    break;
                default:
                    throw new WikiBrainWebException("Unsupported entity type: " + entity1.getType());
            }
            System.out.println("\t7");
            Double sim = (r != null && r.isValid()) ? r.getScore() : null;
            System.out.println("\t8 " + sim);
            req.writeJsonResponse("score", sim, "entity1", entity1.toJson(), "entity2", entity2.toJson());
            System.out.println("\t9");
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println(e.getLocalizedMessage());
        }
        System.out.println("\t10");
    }

    public static void main(String args[]) throws Exception {
        Options options = new Options();
        options.addOption(
                new DefaultOptionBuilder()
                        .withLongOpt("port")
                        .withDescription("Server port number")
                        .create("p"));
        options.addOption(
                new DefaultOptionBuilder()
                        .withLongOpt("listeners")
                        .withDescription("Size of listener queue")
                        .create("q"));

        EnvBuilder.addStandardOptions(options);

        CommandLineParser parser = new PosixParser();
        CommandLine cmd;
        try {
            cmd = parser.parse(options, args);
        } catch (ParseException e) {
            System.err.println( "Invalid option usage: " + e.getMessage());
            new HelpFormatter().printHelp("DumpLoader", options);
            return;
        }

        Env env = new EnvBuilder(cmd).build();

        int port = Integer.valueOf(cmd.getOptionValue("p", "9000"));
        int queueSize = Integer.valueOf(cmd.getOptionValue("q", "100"));
        Server server = new Server(new QueuedThreadPool(queueSize, 20));
        server.setHandler(new CompariFactServer(env));
        ServerConnector sc = new ServerConnector(server);
        sc.setPort(port);
        server.setConnectors(new Connector[]{sc});
        server.start();
        server.join();
    }
}
