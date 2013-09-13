// Copyright 2013 Google Inc. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.enterprise.adaptor.ad;

import com.google.enterprise.adaptor.AbstractAdaptor;
import com.google.enterprise.adaptor.AdaptorContext;
import com.google.enterprise.adaptor.Config;
import com.google.enterprise.adaptor.DocIdPusher;
import com.google.enterprise.adaptor.GroupPrincipal;
import com.google.enterprise.adaptor.Principal;
import com.google.enterprise.adaptor.Request;
import com.google.enterprise.adaptor.Response;
import com.google.enterprise.adaptor.UserPrincipal;

import java.io.*;
import java.text.MessageFormat;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.naming.InterruptedNamingException;

/** Adaptor for Active Directory. */
public class AdAdaptor extends AbstractAdaptor {
  private static final Logger log
      = Logger.getLogger(AdAdaptor.class.getName());
  private static final boolean CASE_SENSITIVITY = false;

  private String namespace;
  private String defaultUser;  // used if an AD doesn't override
  private String defaultPassword;  
  private List<AdServer> servers = new ArrayList<AdServer>();
  private Map<String, String> localizedStrings;

  @Override
  public void initConfig(Config config) {
    config.addKey("ad.servers", null);
    config.addKey("adaptor.namespace", "Default");
    config.addKey("ad.defaultUser", null);
    config.addKey("ad.defaultPassword", null);
    config.addKey("ad.localized.Everyone", "Everyone");
    config.addKey("ad.localized.NTAuthority", "NT Authority");
    config.addKey("ad.localized.Interactive", "Interactive");
    config.addKey("ad.localized.AuthenticatedUsers", "Authenticated Users");
  }

  @Override
  public void init(AdaptorContext context) throws Exception {
    namespace = context.getConfig().getValue("adaptor.namespace");
    log.config("common namespace: " + namespace);
    defaultUser = context.getConfig().getValue("ad.defaultUser");
    defaultPassword = context.getConfig().getValue("ad.defaultPassword");

    List<Map<String, String>> serverConfigs
        = context.getConfig().getListOfConfigs("ad.servers");
    servers.clear();  // in case init gets called again
    for (Map<String, String> singleServerConfig : serverConfigs) {
      String host = singleServerConfig.get("host");
      int port = 389;
      if (singleServerConfig.containsKey("port")) {
        port = Integer.parseInt(singleServerConfig.get("port"));
      }
      Method method = Method.STANDARD;
      if (singleServerConfig.containsKey("method")) {
        String methodStr = singleServerConfig.get("method").toLowerCase();
        if ("ssl".equals(methodStr)) {
          method = Method.SSL;
        } else if (!"standard".equals(methodStr)) {
          throw new IllegalArgumentException("invalid method: " + methodStr);
        }
      }
      String principal = singleServerConfig.get("user");
      String passwd = singleServerConfig.get("password");
      if (null == principal || principal.isEmpty()) {
        principal = defaultUser;
        if (null == passwd || passwd.isEmpty()) {
          passwd = defaultPassword;
        } else {
          String err = "password without user for " + host;
          throw new IllegalStateException(err);
        }
      }
      if (null == passwd || passwd.isEmpty()) {
        String err = "no password for " + host;
        throw new IllegalStateException(err);
      }
      AdServer adServer = new AdServer(method, host, port, principal, passwd);
      servers.add(adServer);
      Map<String, String> dup = new TreeMap<String, String>(singleServerConfig);
      dup.put("password", "XXXXXX");  // hide password
      log.log(Level.CONFIG, "AD server spec: {0}", dup);
    }
    localizedStrings = context.getConfig().getValuesWithPrefix("ad.localized.");    
  }

  /** This adaptor does not serve documents. */
  @Override
  public void getDocContent(Request req, Response resp) throws IOException {
    resp.respondNotFound();
  }

  /** Call default main for adaptors. */
  public static void main(String[] args) {
    AbstractAdaptor.main(new AdAdaptor(), args);
  }

  /** Pushes all groups from all AdServers. */
  @Override
  public void getDocIds(DocIdPusher pusher) throws InterruptedException,
      IOException {
    // TODO(pjo): implement built in groups
    GroupCatalog cumulativeCatalog = new GroupCatalog();
    for (AdServer server : servers) {
      server.initialize();
      try {
        GroupCatalog catalog = new GroupCatalog();
        catalog.readFrom(server);
        cumulativeCatalog.add(catalog);
      } catch (InterruptedNamingException ine) {
        String host = server.getHostName();
        throw new IOException("could not get entities from " + host, ine);
      }
    } 
    cumulativeCatalog.resolveForeignSecurityPrincipals();
    Map<GroupPrincipal, List<Principal>> groups = cumulativeCatalog.makeDefs();
    cumulativeCatalog.clear();
    cumulativeCatalog = null;
    pusher.pushGroupDefinitions(groups, CASE_SENSITIVITY);
  }

  // Space for all group info, organized in different ways
  private class GroupCatalog {
    Set<AdEntity> entities = new HashSet<AdEntity>();
    Map<AdEntity, Set<String>> members = new HashMap<AdEntity, Set<String>>();

    Map<String, AdEntity> bySid = new HashMap<String, AdEntity>();
    Map<String, AdEntity> byDn = new HashMap<String, AdEntity>();
    Map<AdEntity, String> domain = new HashMap<AdEntity, String>();
    
    final AdEntity everyone = new AdEntity("S-1-1-0",
        MessageFormat.format("CN={0}",
        localizedStrings.get("Everyone"))); 
    final AdEntity interactive = new AdEntity("S-1-5-4", 
        MessageFormat.format("CN={0},DC={1}",
        localizedStrings.get("Interactive"),
        localizedStrings.get("NTAuthority")));
    final AdEntity authenticatedUsers = new AdEntity("S-1-5-11" ,
        MessageFormat.format("CN={0},DC={1}", 
        localizedStrings.get("AuthenticatedUsers"), 
        localizedStrings.get("NTAuthority")));
    final Map<AdEntity, Set<String>> wellKnownMembership;
    
    public GroupCatalog() {
      wellKnownMembership = new HashMap<AdEntity, Set<String>>();
      wellKnownMembership.put(everyone, new TreeSet<String>());
      wellKnownMembership.put(interactive, new TreeSet<String>());
      wellKnownMembership.put(authenticatedUsers, new TreeSet<String>());
      
      // To save space on GSA onboard groups database, we add "everyone" as a
      // member to "Interactive" and "authenticated users" groups.
      // Each user from domain will be added as member of "everyone" group 
      // and user will be indirect member for 
      // "Interactive" and "authenticated users" groups.
      wellKnownMembership.get(interactive).add(everyone.getDn());
      wellKnownMembership.get(authenticatedUsers).add(everyone.getDn());
      
      entities.add(everyone);
      entities.add(interactive);
      entities.add(authenticatedUsers);
      
      bySid.put(everyone.getSid(), everyone);
      byDn.put(everyone.getDn(), everyone);
      
      bySid.put(interactive.getSid(), interactive);
      byDn.put(interactive.getDn(), interactive);
      domain.put(interactive, localizedStrings.get("NTAuthority"));
      
      bySid.put(authenticatedUsers.getSid(), authenticatedUsers);
      byDn.put(authenticatedUsers.getDn(), authenticatedUsers);
      domain.put(authenticatedUsers, localizedStrings.get("NTAuthority"));
    }
    
    void readFrom(AdServer server) throws InterruptedNamingException {
      entities = server.search(AdConstants.LDAP_QUERY, /*deleted=*/ false,
          new String[] {
              AdConstants.ATTR_USNCHANGED,
              AdConstants.ATTR_SAMACCOUNTNAME,
              AdConstants.ATTR_OBJECTSID,
              AdConstants.ATTR_OBJECTGUID,
              AdConstants.ATTR_UPN,
              AdConstants.ATTR_PRIMARYGROUPID,
              AdConstants.ATTR_MEMBER}
      );
      log.log(Level.FINE, "received {0} entities from server", entities.size());
      for (AdEntity e : entities) {
        bySid.put(e.getSid(), e);
        byDn.put(e.getDn(), e);
        // TODO(pjo): Have AdServer put domain into AdEntity during search
        domain.put(e, server.getnETBIOSName());
      }
      initializeMembers();
      resolvePrimaryGroups();
    }

    private void initializeMembers() {
      for (AdEntity entity : entities) {
        if (entity.isGroup()) {
          members.put(entity, new TreeSet<String>(entity.getMembers()));
        }
      }
    }

    private void resolvePrimaryGroups() {
      int nadds = 0;
      for (AdEntity e : entities) {
        if (e.isGroup()) {
          continue;
        }
        AdEntity user = e;
        AdEntity primaryGroup = bySid.get(user.getPrimaryGroupSid());
        if (!members.containsKey(primaryGroup)) {
          members.put(primaryGroup, new TreeSet<String>());
        }
        members.get(primaryGroup).add(user.getDn());
        wellKnownMembership.get(everyone).add(user.getDn());
        nadds++;
      }
      log.log(Level.FINE, "# primary groups: {0}", members.keySet().size());
      log.log(Level.FINE, "# users added to all primary groups: {0}", nadds);
    }

    void resolveForeignSecurityPrincipals() {
      int nGroups = 0;
      int nNullSid = 0;
      int nNullResolution = 0;
      int nResolved = 0;
      for (AdEntity entity : entities) {
        if (!entity.isGroup() || entity.isWellKnown()) {
          continue;
        }
        nGroups++;
        Set<String> resolvedMembers = new HashSet<String>();
        for (String member : members.get(entity)) {
          String sid = AdEntity.parseForeignSecurityPrincipal(member);
          if (null == sid) {
            resolvedMembers.add(member);
            nNullSid++;
          } else {
            AdEntity resolved = bySid.get(sid);
            if (null == resolved) {
              log.info("unable to resolve foreign principal ["
                  + member + "]; member of [" + entity.getDn());
              nNullResolution++;
            } else {
              resolvedMembers.add(resolved.getDn());
              nResolved++;
            }
          }
        }
        members.put(entity, resolvedMembers);
      }
      log.log(Level.FINE, "#groups: {0}", nGroups);
      log.log(Level.FINE, "#null-SID: {0}", nNullSid);
      log.log(Level.FINE, "#null-resolve: {0}", nNullResolution);
      log.log(Level.FINE, "#resolved: {0}", nResolved);
    }

    Map<GroupPrincipal, List<Principal>> makeDefs() {
      // Merge members with well known group members
      Map<AdEntity, Set<String>> allMembers 
          = new HashMap<AdEntity,Set<String>>(members);
      allMembers.putAll(wellKnownMembership);
      Map<GroupPrincipal, List<Principal>> groups
          = new HashMap<GroupPrincipal, List<Principal>>();
      for (AdEntity entity : entities) {
        if (!entity.isGroup()) {
          continue;
        }
        String groupName = getPrincipalName(entity);
        GroupPrincipal group = new GroupPrincipal(groupName, namespace);
        List<Principal> def = new ArrayList<Principal>();
        if (!allMembers.containsKey(entity)) {
          continue;
        }
        for (String memberDn : allMembers.get(entity)) {
          AdEntity member = byDn.get(memberDn);
          if (member == null) {
            log.info("Unknown member [" + memberDn + "] of group ["
                + entity.getDn());
            continue;
          }
          Principal p;
          String memberName = getPrincipalName(member);
          if (member.isGroup()) {
            p = new GroupPrincipal(memberName, namespace);
          } else {
            p = new UserPrincipal(memberName, namespace);
          }
          def.add(p);
        }
        if (entity.isWellKnown()) {
          log.log(Level.FINE, "Well known group {0} with # members {1}",
              new Object[]{group, def.size()});
        }
        // TODO(pjo): send empty groups?
        if (0 != def.size()) {
          groups.put(group, def);
        }
      }
      log.log(Level.FINE, "number of groups defined: {0}",
           groups.keySet().size());
      if (log.isLoggable(Level.FINER)) {
        int numGroups = groups.keySet().size();
        int totalMembers = 0;
        for (List<Principal> def : groups.values()) {
          totalMembers += def.size();
        }
        if (0 != numGroups) {
          double mean = ((double) totalMembers) / numGroups;
          log.finer("mean size of defined group: " + mean);
        }
      }
      return groups;
    }
    
    /*
     * returns principal name for ADEntity object. if domain is available return
     * principal name as samaccountname@domain else just use samaccountname as 
     * principal name.
     */
    String getPrincipalName(AdEntity e) {
      return domain.get(e) != null ?
          e.getSAMAccountName() + "@" + domain.get(e) : e.getSAMAccountName();
    } 

    /* Combines info of another catalog with this one. */
    void add(GroupCatalog other) {
      entities.addAll(other.entities);
      members.putAll(other.members);
      bySid.putAll(other.bySid);
      byDn.putAll(other.byDn);
      domain.putAll(other.domain);
      //TODO: Add equal method to AdEntity so that we can loop here on keyset
      // for wellKnownMembership
      wellKnownMembership.get(everyone).addAll(
          other.wellKnownMembership.get(other.everyone));
      wellKnownMembership.get(interactive).addAll(
          other.wellKnownMembership.get(other.interactive));
      wellKnownMembership.get(authenticatedUsers).addAll(
          other.wellKnownMembership.get(other.authenticatedUsers));
    }

    void clear() {
      entities.clear();
      members.clear();
      bySid.clear();
      byDn.clear();
      domain.clear();
      wellKnownMembership.clear();
    }
  }
}