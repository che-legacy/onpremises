/*
 *  2012-2016 Codenvy, S.A.
 *  All Rights Reserved.
 *
 * NOTICE:  All information contained herein is, and remains
 * the property of Codenvy S.A. and its suppliers,
 * if any.  The intellectual and technical concepts contained
 * herein are proprietary to Codenvy S.A.
 * and its suppliers and may be covered by U.S. and Foreign Patents,
 * patents in process, and are protected by trade secret or copyright law.
 * Dissemination of this information or reproduction of this material
 * is strictly forbidden unless prior written permission is obtained
 * from Codenvy S.A..
 */
package com.codenvy.cloudide;


import com.codenvy.auth.sso.client.CacheDisablingFilter;
import com.codenvy.auth.sso.client.LoginFilter;
import com.codenvy.auth.sso.client.RecoverableTokenHandler;
import com.codenvy.auth.sso.client.deploy.SsoClientServletModule;
import com.codenvy.cloudide.maintenance.MaintenanceStatusServlet;
import com.google.inject.name.Names;
import com.google.inject.servlet.ServletModule;

import org.eclipse.che.inject.DynaModule;

/** Servlet module composer for user dashboard war. */

@DynaModule
public class CloudIdeUserDashboardServletModule extends ServletModule {
    @Override
    protected void configureServlets() {
        bind(com.codenvy.auth.sso.client.TokenHandler.class).to(RecoverableTokenHandler.class);
        bindConstant().annotatedWith(Names.named("auth.sso.login_page_url")).to("/site/login");
        bindConstant().annotatedWith(Names.named("auth.sso.cookies_disabled_error_page_url"))
                      .to("/site/error/error-cookies-disabled");

        filterRegex("/(?!_sso/).*$").through(CacheDisablingFilter.class);

        filterRegex("/(?!_sso/).*$").through(LoginFilter.class);

        serve("/scheduled").with(MaintenanceStatusServlet.class);

        install(new SsoClientServletModule());
    }
}
