package com.marklogic.hub.web.controller.api;

import java.io.File;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.http.MediaType;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.marklogic.hub.config.EnvironmentConfiguration;
import com.marklogic.hub.exception.DataHubException;
import com.marklogic.hub.model.EntityModel;
import com.marklogic.hub.service.DataHubService;
import com.marklogic.hub.service.EntityManagerService;
import com.marklogic.hub.service.SyncStatusService;
import com.marklogic.hub.web.controller.BaseController;
import com.marklogic.hub.web.form.LoginForm;

@RestController
@RequestMapping("/api/data-hub")
@Scope("session")
public class DataHubServerApiController extends BaseController {
	private static final Logger LOGGER = LoggerFactory.getLogger(DataHubServerApiController.class);

	@Autowired
	private EnvironmentConfiguration environmentConfiguration;

	@Autowired
	private DataHubService dataHubService;

	@Autowired
	private EntityManagerService entityManagerService;

	@Autowired
	private SyncStatusService syncStatusService;

	@RequestMapping(value = "login", method = RequestMethod.POST, consumes = {
			MediaType.APPLICATION_JSON_UTF8_VALUE }, produces = { MediaType.APPLICATION_JSON_UTF8_VALUE })
	public LoginForm postLogin(@RequestBody LoginForm loginForm, BindingResult bindingResult, HttpSession session,
			HttpServletRequest request) throws Exception {
		LOGGER.debug("POST: login");
		try {
			if (isValidDirectory(loginForm.getUserPluginDir())) {

				updateEnvironmentConfiguration(loginForm);

				loginForm.setInstalled(dataHubService.isInstalled());
				loginForm.setServerVersionAccepted(dataHubService.isServerAcceptable());
				loginForm.setHasErrors(false);
				loginForm.setLoggedIn(true);
				loginForm.setUninstalling(false);
				environmentConfiguration.saveConfigurationToFile();
				session.setAttribute("loginForm", loginForm);

				if (loginForm.isInstalled()) {
					this.loadUserModules(loginForm);
				} 

			} else {
				loginForm.setLoggedIn(false);
				displayError(loginForm, null, null, loginForm.getUserPluginDir() + " is not a valid directory.");
			}
		} catch (DataHubException e) {
			LOGGER.error("Login failed", e);
			loginForm.setLoggedIn(false);
			displayError(loginForm, null, null, e.getMessage());
		}

		return loginForm;
	}

	private void loadUserModules(LoginForm loginForm) {
		loginForm.setEntities(entityManagerService.getEntities());
		loginForm.setSelectedEntity(loginForm.getEntities() != null && !loginForm.getEntities().isEmpty()
				? loginForm.getEntities().get(0) : null);
	}

	private void unLoadUserModules(LoginForm loginForm) {
		loginForm.setEntities(new ArrayList<EntityModel>());
		loginForm.setSelectedEntity(null);
	}

	private boolean isValidDirectory(String userPluginDir) {
		File file = new File(userPluginDir);
		if (file.exists() && file.isDirectory()) {
			return true;
		}

		File parentFile = file.getParentFile();
		if (parentFile.exists() && parentFile.isDirectory()) {
			file.mkdir();
			return true;
		}
		return false;
	}

	@RequestMapping(value = "login-status", method = RequestMethod.GET)
	public LoginForm getLoginStatus(HttpSession session) {
		LOGGER.debug("GET: login-status");
		LoginForm loginForm = (LoginForm) session.getAttribute("loginForm");
		
		if (loginForm == null) {
			// need to load last configuration
			loginForm = new LoginForm();
			this.environmentConfiguration.loadConfigurationFromFiles();
			this.retrieveEnvironmentConfiguration(loginForm);
			loginForm.setUninstalling(false);
			session.setAttribute("loginForm", loginForm);
		} else if (loginForm.isInstalled()) {
			loginForm.setEntities(entityManagerService.getEntities());
			loginForm.refreshSelectedEntity();
			synchronized (syncStatusService) {
				LOGGER.debug("installing modules ...");
				this.installUserModules(session);
				LOGGER.debug("modules installed.");
			}
		}

		LOGGER.debug("after login: " + loginForm.toString());

		return loginForm;
	}

	@RequestMapping(value = "logout", method = RequestMethod.POST)
	public LoginForm postLogout(HttpSession session) {
		LOGGER.debug("POST: logout");
		LoginForm loginForm = (LoginForm) session.getAttribute("loginForm");
		loginForm.setLoggedIn(false);
		loginForm.setUninstalling(false);
		this.retrieveEnvironmentConfiguration(loginForm);

		Enumeration<String> attrNames = session.getAttributeNames();
		while (attrNames.hasMoreElements()) {
			session.removeAttribute(attrNames.nextElement());
		}

		return loginForm;
	}

	@RequestMapping(value = "install", method = RequestMethod.POST)
	public LoginForm install(HttpSession session) {
		LOGGER.debug("POST: install");
		dataHubService.install();
		LoginForm loginForm = (LoginForm) session.getAttribute("loginForm");
		loginForm.setInstalled(true);
		this.loadUserModules(loginForm);
		return loginForm;
	}

	@RequestMapping(value = "uninstall", method = RequestMethod.POST)
	public LoginForm uninstall(HttpSession session) {
		LOGGER.debug("POST: uninstall");
		LoginForm loginForm = (LoginForm) session.getAttribute("loginForm");
		loginForm.setUninstalling(true);
		dataHubService.uninstall();
		loginForm.setInstalled(false);
		this.unLoadUserModules(loginForm);
		return loginForm;
	}

	@RequestMapping(value = "install-user-modules", method = RequestMethod.POST)
	public LoginForm installUserModules(HttpSession session) {
		LOGGER.debug("POST: install-user-modules");
		synchronized (syncStatusService) {
			dataHubService.installUserModules();

			// refresh the list of entities saved in the session
			LoginForm loginForm = (LoginForm) session.getAttribute("loginForm");
			loginForm.setEntities(entityManagerService.getEntities());
			loginForm.refreshSelectedEntity();
			syncStatusService.notifyAll();

			return loginForm;
		}
	}

	@RequestMapping(value = "validate-user-modules", method = RequestMethod.GET)
	public JsonNode validateUserModules(HttpSession session) {
		LOGGER.debug("GET: validate-user-modules");
		synchronized (syncStatusService) {
			return dataHubService.validateUserModules();
		}
	}

	private void updateEnvironmentConfiguration(LoginForm loginForm) {
		environmentConfiguration.setMLHost(loginForm.getMlHost());
		environmentConfiguration.setMLStagingPort(loginForm.getMlStagingPort());
		environmentConfiguration.setMLFinalPort(loginForm.getMlFinalPort());
		environmentConfiguration.setMlTracePort(loginForm.getMlTracePort());
		environmentConfiguration.setMLUsername(loginForm.getMlUsername());
		environmentConfiguration.setMLPassword(loginForm.getMlPassword());
		environmentConfiguration.setUserPluginDir(loginForm.getUserPluginDir());
	}

	private void retrieveEnvironmentConfiguration(LoginForm loginForm) {
		loginForm.setMlHost(environmentConfiguration.getMLHost());
		loginForm.setMlStagingPort(environmentConfiguration.getMLStagingPort());
		loginForm.setMlFinalPort(environmentConfiguration.getMLFinalPort());
		loginForm.setMlTracePort(environmentConfiguration.getMLTracePort());
		loginForm.setMlUsername(environmentConfiguration.getMLUsername());
		loginForm.setMlPassword(environmentConfiguration.getMLPassword());
		loginForm.setUserPluginDir(environmentConfiguration.getUserPluginDir());
	}
}
