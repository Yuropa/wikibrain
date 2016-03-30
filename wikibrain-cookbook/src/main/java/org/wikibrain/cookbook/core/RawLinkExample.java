package org.wikibrain.cookbook.core;

import org.wikibrain.conf.ConfigurationException;
import org.wikibrain.conf.Configurator;
import org.wikibrain.core.cmd.Env;
import org.wikibrain.core.cmd.EnvBuilder;
import org.wikibrain.core.dao.*;
import org.wikibrain.core.lang.Language;
import org.wikibrain.core.model.*;

/**
 * Created by Josh on 3/29/16.
 */
public class RawLinkExample {

    public static void main(String args[]) throws ConfigurationException, DaoException {
        // The following ten-line dance to get an env is awkward and repeated over and over.
        // Figure out a good way to consolidate it.
        Env env = EnvBuilder.envFromArgs(args);

        Configurator configurator = env.getConfigurator();
        RawLinkDao rlDao = configurator.get(RawLinkDao.class);
        LocalPageDao lpDao = configurator.get(LocalPageDao.class);
        Language simple = env.getLanguages().getDefaultLanguage();

        LocalPage page = lpDao.getByTitle(new Title("List of Soundgarden band members", simple), NameSpace.ARTICLE);
        System.out.println("page is " + page);

        for (RawLink link : rlDao.getLinks(page.getLanguage(), page.getLocalId())) {
            System.out.println("link is: " + link);
        }
    }
}
