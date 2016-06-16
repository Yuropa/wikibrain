package org.wikibrain.webapi;

import com.google.gdata.util.ServiceException;
import org.apache.commons.cli.*;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wikibrain.conf.ConfigurationException;
import org.wikibrain.conf.DefaultOptionBuilder;
import org.wikibrain.core.cmd.Env;
import org.wikibrain.core.cmd.EnvBuilder;
import org.wikibrain.core.dao.*;
import org.wikibrain.core.lang.Language;
import org.wikibrain.core.model.LocalPage;
import org.wikibrain.core.model.RawImage;
import org.wikibrain.sr.SRMetric;
import org.wikibrain.sr.SRResult;
import org.wikibrain.sr.wikify.Wikifier;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLDecoder;
import java.security.GeneralSecurityException;
import java.util.*;

/**
 * @author Shilad Sen
 * This is based on the WikiBrain Server Shilad wrote. I removed some of the features I don't need and added some features
 * that we needed
 */
public class CompariFactServer extends AbstractHandler {
    private static final Logger LOG = LoggerFactory.getLogger(CompariFactServer.class);
    private final Env env;
    private WebEntityParser entityParser;
    private List<CompariFactDataSource> sources;
    private PageDownloader pageDownloader;

    public CompariFactServer(Env env) throws ConfigurationException, DaoException, IOException, GeneralSecurityException, ServiceException, InterruptedException {
        this.env = env;
        this.entityParser = new WebEntityParser(env);

        pageDownloader = new PageDownloader();

        // Warm up necessary components
        for (Language l : env.getLanguages()) {
            LOG.info("warming up components for language: " + l);
            env.getConfigurator().get(Wikifier.class, "websail", "language", l.getLangCode());
            env.getConfigurator().get(SRMetric.class,"ESA", "language", l.getLangCode());
            env.getConfigurator().get(SRMetric.class,"simple-ensemble", "language", l.getLangCode());
        }

        // Create and add all the image sources
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
            } else if (target.equals("/imagePages")) {
                doImagePages(req);
            } else if (target.equals("/extract")) {
                doExtractActicle(req);
            } else if (target.equals("/featured")) {
                doFeatureArticles(req);
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

    // The main method. This will find images from Wikipedia from text
    private void doImages(WikiBrainWebRequest req) throws ConfigurationException, DaoException {
        // Get search infomration
        Language lang = req.getLanguage();
        String type = req.getParamOrDie("method");
        String text = URLDecoder.decode(req.getParamOrDie("text"));

        LOG.info("Generating images using method " + type + " for text: " + text);

        ArrayList<InternalImage> images = new ArrayList<InternalImage>();

        // Iterator overall the image sources we have and add all their generated iamges
        // These are configured in the constructor
        for (CompariFactDataSource source : sources) {
            images.addAll(source.generateimages(text, type));
        }

        Set<String> imageLocations = new HashSet<String>();

        // Remove duplicate images by looking at image URLs
        // This won't remove duplicate Reference Maps
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

        // Convert all the iamges to JSON
        List jsonConcepts = new ArrayList();
        for (InternalImage i : images) {
            try {
                List imageURLS = new ArrayList();
                Map image = new HashMap();

                // Skip the photos
                if (i.isPhotograph()) {
                    continue;
                }

                // We need to handle images and reference maps seperatly
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

    // This performs ESA between two terms
    private void doESA(WikiBrainWebRequest req) throws ConfigurationException, DaoException {
        // TODO: support explanations
        Language lang = req.getLanguage();
        List<WebEntity> entities = entityParser.extractEntityList(req);
        if (entities.size() != 2) {
            throw new WikiBrainWebException("Similarity requires exactly two entities");
        }
        // Get the terms
        WebEntity entity1 = entities.get(0);
        WebEntity entity2 = entities.get(1);
        SRMetric sr = getESA(lang);
        SRResult r = null;

        // Perform the SR calculation based on type
        switch (entity1.getType()) {
            case ARTICLE_ID:
            case TITLE:
                r = sr.similarity(entity1.getArticleId(), entity2.getArticleId(), false);
                break;
            case PHRASE:
                r = sr.similarity(entity1.getPhrase(), entity2.getPhrase(), false);
                break;
            default:
                throw new WikiBrainWebException("Unsupported entity type: " + entity1.getType());
        }

        // Return the result
        Double sim = (r != null && r.isValid()) ? r.getScore() : null;
        req.writeJsonResponse("score", sim, "entity1", entity1.toJson(), "entity2", entity2.toJson());
    }

    // This performs reverse image search, so given an image with a title
    private void doImagePages(WikiBrainWebRequest req) throws ConfigurationException, DaoException {
        // Process the request and get the text and language
        Language lang = req.getLanguage();
        List<WebEntity> entities = entityParser.extractEntityList(req);
        if (entities.size() != 1) {
            throw new WikiBrainWebException("Image pages requires exactly one entry");
        }
        WebEntity entity1 = entities.get(0);

        // Look up the image by title
        RawImageDao riDao = env.getConfigurator().get(RawImageDao.class);
        RawImage image = riDao.getImage(entity1.getTitle());
        List<Map<String, String>> pages = new ArrayList<Map<String, String>>();

        // Find all the wikipedia pages which contain the image
        Iterator<LocalPage> imagePages = riDao.pagesWithImage(image, lang);
        while (imagePages.hasNext()) {
            LocalPage lp = imagePages.next();

            // Add the page to the result
            Map<String, String> data = new HashMap<String, String>();
            data.put("title", lp.getTitle().getCanonicalTitle());
            data.put("id", lp.getLocalId() + "");

            pages.add(data);
        }

        req.writeJsonResponse("pages", pages, "entity1", entity1.toJson());
    }

    private void doExtractActicle(WikiBrainWebRequest req) throws ConfigurationException, DaoException {
        String url = req.getParamOrDie("url");
        PageDownloader.Article page = pageDownloader.pageForURL(url);

        /*
        public String content;
        public String date;
        public String title;
        public String imageURL;
         */
        req.writeJsonResponse("content", page.content, "date", page.date, "title", page.title, "imageURL", page.imageURL);
    }

    private void doFeatureArticles(WikiBrainWebRequest req) {
        JSONArray data = new JSONArray();

        for (PageDownloader.ArticleSection s : pageDownloader.getFeaturedArticles()) {
            JSONArray articles = new JSONArray();
            for (PageDownloader.Article a : s.getArticles()) {
                JSONObject article = new JSONObject();
                article.put("title", a.title);
                article.put("url", a.url);
                article.put("imageURL", a.imageURL);
                articles.put(article);
            }

            JSONObject section = new JSONObject();
            section.put("title", s.getTitle());
            section.put("articles", articles);
            data.put(section);
        }

        req.writeJsonResponse("data", data.toString());
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

        ContextHandler context = new ContextHandler();
        context.setContextPath("/comparifact");
        context.setHandler(new CompariFactServer(env));
        server.setHandler( context );

        ServerConnector sc = new ServerConnector(server);
        sc.setPort(port);
        server.setConnectors(new Connector[]{sc});
        server.start();
        server.join();
    }
}
