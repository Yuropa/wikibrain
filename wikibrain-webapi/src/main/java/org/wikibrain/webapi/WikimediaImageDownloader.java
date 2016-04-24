package org.wikibrain.webapi;

import org.apache.commons.cli.*;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
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

import java.util.Iterator;

/**
 * Created by Josh on 4/22/16.
 */
public class WikimediaImageDownloader {
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

    private void downloadImagesForPage(int localId) throws DaoException {

    }

    private void downloadImages(int offset, int count) throws DaoException {
        DaoFilter filter = new DaoFilter();
        filter.setLanguages(lang);
        Iterator<LocalPage> pages = lpDao.get(filter).iterator();

    }

    void main(String[] args) throws ConfigurationException, DaoException {
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
                        .withDescription("Starting offset in the list of page ids")
                        .create("s"));
        options.addOption(
                new DefaultOptionBuilder()
                        .withLongOpt("count")
                        .withDescription("The number of pages to process")
                        .create("c"));

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

        int start = Integer.parseInt(cmd.getOptionValue("s", "0"));
        int end   = Integer.parseInt(cmd.getOptionValue("e", Integer.toString(Integer.MAX_VALUE)));

        downloader.downloadImages(start, end);
    }
}
