/*******************************************************************************
 * Copyright (c) 2017 Microsoft Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Microsoft Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.ls.core.internal.handlers;

import static org.eclipse.jdt.ls.core.internal.JavaLanguageServerPlugin.logError;
import static org.eclipse.jdt.ls.core.internal.JavaLanguageServerPlugin.logException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.jdt.core.IJavaModelMarker;
import org.eclipse.jdt.ls.core.internal.BuildWorkspaceStatus;
import org.eclipse.jdt.ls.core.internal.JDTUtils;
import org.eclipse.jdt.ls.core.internal.JavaClientConnection;
import org.eclipse.jdt.ls.core.internal.ProjectUtils;
import org.eclipse.jdt.ls.core.internal.ResourceUtils;
import org.eclipse.jdt.ls.core.internal.managers.ProjectsManager;
import org.eclipse.lsp4j.PublishDiagnosticsParams;

/**
 * @author xuzho
 *
 */
public class BuildWorkspaceHandler {
	private JavaClientConnection connection;
	private final ProjectsManager projectsManager;

	public BuildWorkspaceHandler(JavaClientConnection connection, ProjectsManager projectsManager) {
		this.connection = connection;
		this.projectsManager = projectsManager;
	}

	public BuildWorkspaceStatus buildWorkspace(boolean forceReBuild, IProgressMonitor monitor) {
		try {
			if (monitor.isCanceled()) {
				return BuildWorkspaceStatus.CANCELLED;
			}
			projectsManager.cleanupResources(projectsManager.getDefaultProject());
			IProject[] projects = ProjectUtils.getAllProjects();
			for (IProject project : projects) {
				if (!project.equals(projectsManager.getDefaultProject())) {
					String uri = JDTUtils.getFileURI(project);
					connection.publishDiagnostics(new PublishDiagnosticsParams(ResourceUtils.toClientUri(uri), Collections.emptyList()));
				}
			}
			if (forceReBuild) {
				ResourcesPlugin.getWorkspace().build(IncrementalProjectBuilder.CLEAN_BUILD, monitor);
				ResourcesPlugin.getWorkspace().build(IncrementalProjectBuilder.FULL_BUILD, monitor);
			} else {
				ResourcesPlugin.getWorkspace().build(IncrementalProjectBuilder.INCREMENTAL_BUILD, monitor);
			}
			List<IMarker> problemMarkers = new ArrayList<>();
			for (IProject project : projects) {
				if (!project.equals(projectsManager.getDefaultProject())) {
					List<IMarker> markers = ResourceUtils.getErrorMarkers(project);
					if (markers != null) {
						problemMarkers.addAll(markers);
					}
				}
			}
			List<String> errors = problemMarkers.stream().filter(m -> m.getAttribute(IMarker.SEVERITY, 0) == IMarker.SEVERITY_ERROR).map(e -> convertMarker(e)).collect(Collectors.toList());
			if (errors.isEmpty()) {
				return BuildWorkspaceStatus.SUCCEED;
			} else {
				// for default project, problem markers aren't sent. Add logs here for trouble shooting.
				String newline = System.getProperty("line.separator");
				logError("Error occured while building workspace. Details: " + newline + String.join(newline, errors));
				return BuildWorkspaceStatus.WITH_ERROR;
			}
		} catch (CoreException e) {
			logException("Failed to build workspace.", e);
			return BuildWorkspaceStatus.FAILED;
		} catch (OperationCanceledException e) {
			return BuildWorkspaceStatus.CANCELLED;
		}
	}

	private static String convertMarker(IMarker marker) {
		StringBuilder builder = new StringBuilder();
		String message = marker.getAttribute(IMarker.MESSAGE, "<no message>");
		String code = String.valueOf(marker.getAttribute(IJavaModelMarker.ID, 0));
		builder.append(" message: ").append(message).append(";");
		builder.append(" code: ").append(code).append(";");
		IResource resource = marker.getResource();
		if (resource != null) {
			builder.append(" resource: ").append(resource.getLocation()).append(";");
		}
		int line = marker.getAttribute(IMarker.LINE_NUMBER, -1);
		if (line > 0) {
			builder.append(" line: ").append(line);
		}
		return builder.toString();
	}
}
