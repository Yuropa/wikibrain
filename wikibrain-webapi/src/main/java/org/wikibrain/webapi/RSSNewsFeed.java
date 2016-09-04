package org.wikibrain.webapi;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.feed.synd.SyndLink;
import com.rometools.rome.io.FeedException;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;

/**
 * Created by Josh on 9/3/16.
 */
public class RSSNewsFeed {
    final public String feedURL;
    private URL url;

    RSSNewsFeed(String feedURL) throws MalformedURLException {
        this.feedURL = feedURL;
        this.url = new URL(feedURL);
    }

    List<String> getCurrentArticles() throws IOException, FeedException {
        SyndFeedInput input = new SyndFeedInput();
        SyndFeed feed = input.build(new XmlReader(url));

        ArrayList<String> links = new ArrayList<String>();
        for (SyndLink link : feed.getLinks()) {
            links.add(link.getHref());
        }
        return links;
    }
}
