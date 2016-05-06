package org.wikibrain.webapi;

import org.apache.commons.cli.*;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wikibrain.conf.ConfigurationException;
import org.wikibrain.conf.Configurator;
import org.wikibrain.conf.DefaultOptionBuilder;
import org.wikibrain.core.cmd.Env;
import org.wikibrain.core.cmd.EnvBuilder;
import org.wikibrain.core.dao.DaoException;
import org.wikibrain.core.dao.DaoFilter;
import org.wikibrain.core.dao.LocalPageDao;
import org.wikibrain.core.dao.RawImageDao;
import org.wikibrain.core.lang.Language;
import org.wikibrain.core.model.LocalLink;
import org.wikibrain.core.model.LocalPage;
import org.wikibrain.core.model.RawImage;
import sun.rmi.runtime.Log;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Iterator;

/**
 * Created by Josh on 4/22/16.
 */
public class WikimediaImageDownloader {
    private static final Logger LOG = LoggerFactory.getLogger(WikimediaImageDownloader.class);

    final RawImageDao riDao;
    final LocalPageDao lpDao;
    final Language lang;
    public String outputDirectory;
    public int maxImageWidth;

    WikimediaImageDownloader(Env env, Configurator conf) throws ConfigurationException {
        lang = env.getDefaultLanguage();
        lpDao = conf.get(LocalPageDao.class);
        riDao = conf.get(RawImageDao.class);
    }

    private void downloadImage(RawImage image) throws DaoException {
        LOG.info("Found image: " + image.getName());
    }

    private void downloadImages(String start, String end) throws DaoException {
        Iterator<RawImage> images = riDao.getImages(start, end);

        while (images.hasNext()) {
            downloadImage(images.next());
        }
    }

    private void pagesWithImage(String imageTitle, Language language) throws DaoException {
        RawImage image = riDao.getImage(imageTitle);
        LOG.info(image.toString());
        LOG.info("Image has the following pages: ");
        Iterator<LocalPage> pages = riDao.pagesWithImage(image, language);


        while (pages.hasNext()) {
            LocalPage page = pages.next();
            if (page != null) {
                // Not all of the pages returned are local pages, some talk pages etc...
                LOG.info(page.toString());
            }
        }
    }

    public static void main(String args[]) throws ConfigurationException, DaoException {
        Options options = new Options();
        options.addOption(
                new DefaultOptionBuilder()
                        .withLongOpt("output")
                        .withDescription("Ouput directory location")
                        .create("o"));
        options.addOption(
                new DefaultOptionBuilder()
                        .withLongOpt("max")
                        .withDescription("Maximum image width, use -1 to impose no limit")
                        .create("m"));

        options.addOption(
                new DefaultOptionBuilder()
                        .withLongOpt("start")
                        .withDescription("Starting image title, does not need to exist")
                        .create("s"));
        options.addOption(
                new DefaultOptionBuilder()
                        .withLongOpt("end")
                        .withDescription("Ending image title, does not need to exist")
                        .create("e"));

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

        WikimediaImageDownloader downloader = new WikimediaImageDownloader(env, env.getConfigurator());
        downloader.outputDirectory = cmd.getOptionValue("o", "./output");
        downloader.maxImageWidth = Integer.parseInt(cmd.getOptionValue("m", "-1"));

        String start = cmd.getOptionValue("s", "a");
        String end   = cmd.getOptionValue("e", "A&SHighlandersGrangegorman.jpg");

        downloader.downloadImages(start, end);
        downloader.pagesWithImage("Castle_Himeji_sakura02.jpg", env.getDefaultLanguage());
    }
}
