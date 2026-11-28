package com.shopwise.discovery;

import java.util.ArrayList;
import java.util.List;

interface CatalogProvider {
    String name();
    boolean enabled();
    List<Offer> search(String query) throws Exception;
    List<Offer> lookup(String id) throws Exception;
    default List<Offer> lookupAll(List<String> ids) throws Exception {
        var offers=new ArrayList<Offer>();
        for (String id:ids) offers.addAll(lookup(id));
        return offers;
    }
    List<Offer> details(String id) throws Exception;
}
