/*******************************************************************************
 * Copyright (c) 2009 Casey Marshall and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors: 
 *    Casey Marshall - initial API and implementation
 *******************************************************************************/
package ccw.launching;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.ProgressMonitorWrapper;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.model.IProcess;
import org.eclipse.jdt.launching.IJavaLaunchConfigurationConstants;
import org.eclipse.jdt.launching.JavaLaunchDelegate;
import org.eclipse.jface.dialogs.ErrorDialog;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.WorkbenchException;
import org.eclipse.ui.console.ConsolePlugin;
import org.eclipse.ui.console.IConsole;
import org.eclipse.ui.console.IConsoleListener;

import ccw.CCWPlugin;
import ccw.ClojureCore;
import ccw.ClojureProject;
import ccw.repl.REPLView;
import ccw.util.BundleUtils;
import ccw.util.ClojureInvoker;
import ccw.util.DisplayUtil;
import clojure.lang.RT;
import clojure.lang.Var;
import clojure.tools.nrepl.Connection;

public class ClojureLaunchDelegate extends JavaLaunchDelegate {

    private static Var currentLaunch = Var.create().setDynamic(true);
    private final ClojureInvoker coreLaunch = ClojureInvoker.newInvoker(CCWPlugin.getDefault(), "ccw.core.launch");
    private static IConsole lastConsoleOpened;
    
    static {
        ConsolePlugin.getDefault().getConsoleManager().addConsoleListener(new IConsoleListener() {
            public void consolesRemoved(IConsole[] consoles) {}
            public void consolesAdded(IConsole[] consoles) {
                lastConsoleOpened = consoles.length > 0 ? consoles[0] : null;
            }
        });
    }
    
    private class REPLViewLaunchMonitor extends ProgressMonitorWrapper {
        private static final int REPL_START_TIMEOUT_MS = 600000;
		private ILaunch launch;
        private final boolean makeActiveREPL;
        
        private REPLViewLaunchMonitor (IProgressMonitor m, ILaunch launch, boolean makeActiveREPL) {
            super(m);
            this.launch = launch;
            this.makeActiveREPL = makeActiveREPL;
        }

        public void done() {
            super.done();

            Job ackJob = new Job("Waiting for new REPL process to be ready...") {
                private IProgressMonitor monitor;
                private CountDownLatch cancelOrAck = new CountDownLatch(1);
                public void canceling () {
                    if (monitor != null) {
                        monitor.setCanceled(true);
                        cancelOrAck.countDown();
                        try {
                            launch.terminate();
                        } catch (DebugException e) {
                            CCWPlugin.logError(e);
                        }
                    }
                }
                
                private IStatus done (IProgressMonitor monitor, IStatus status) {
                    monitor.done();
                    return status;
                }
                
				protected IStatus run(final IProgressMonitor monitor) {
				    this.monitor = monitor;
				    
					monitor.beginTask("Waiting for new REPL process to be ready...", IProgressMonitor.UNKNOWN);

                    final Number port = (Number)Connection.find("clojure.tools.nrepl.ack", "wait-for-ack").invoke(REPL_START_TIMEOUT_MS);
                    cancelOrAck.countDown();

                    if (monitor.isCanceled()) {
                        return done(monitor, Status.CANCEL_STATUS);
                    } else if (port == null) {
                        CCWPlugin.logError("Waiting for new REPL process ack timed out");
                        return done(monitor, new Status(IStatus.ERROR, CCWPlugin.PLUGIN_ID, "Waiting for new REPL process ack timed out"));
                    }
                    
                    coreLaunch._("on-nrepl-server-instanciated", port, LaunchUtils.getProjectName(launch));
                    
                    // The syncExec is necessary to ensure the launch does not return
                    // until either the repl is launched, either it failed
		            DisplayUtil.syncExec(new Runnable() {
		                public void run() {
		                    
		                    // only using a latch because getProject().touch can call done() more than once
		                    final CountDownLatch projectTouchLatch = new CountDownLatch(1);
	                    	if (isAutoReloadEnabled(launch) && getProject() != null) {
                    			try {
	                    			getProject().touch(new NullProgressMonitor() {
	                    				public void done() {
	                    					projectTouchLatch.countDown();
	                    				}
	                    			});
                    			} catch (CoreException e) {
                    				final String MSG = "unexpected exception during project refresh for auto-load on startup";
                    				ErrorDialog.openError(PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell(),
                    						"REPL Connection failure", MSG, e.getStatus());
	                    		}
	                    	} else {
	                    		projectTouchLatch.countDown();
	                    	}
	                    	try {
                                projectTouchLatch.await();
                            } catch (InterruptedException e) {}
	                    	connectRepl();
		                }
		                private IProject getProject() {
		            		try {
		            			return LaunchUtils.getProject(launch);
		            		} catch (CoreException e) {
		            			CCWPlugin.logWarning("Unable to get project for launch configuration", e);
		            			return null;
		            		}
		            	}
		                private void connectRepl() {
		                    try {
		                        REPLView replView = REPLView.connect("nrepl://127.0.0.1:" + port.intValue(), lastConsoleOpened, launch, makeActiveREPL);
		                        String startingNamespace = REPLViewLaunchMonitor.this.launch.getLaunchConfiguration().getAttribute(LaunchUtils.ATTR_NS_TO_START_IN, "user");
		                        try {
		                        	replView.setCurrentNamespace(startingNamespace);
		                        } catch (Exception e) {
		                        	CCWPlugin.logError("Could not start REPL in namespace " + startingNamespace, e);
		                        }
		                        
		                    } catch (Exception e) {
		                        CCWPlugin.logError("Could not connect REPL to local launch", e);
		                    }
		                }
		            });
		            
		            return done(monitor, Status.OK_STATUS);
				}
            };
            ackJob.setUser(true);
            ackJob.schedule();
            
            Thread.yield();
            
            try {
				ackJob.join();
			} catch (InterruptedException e) {
				CCWPlugin.logError("Exception while launching a Clojure Application", e);
			}
        }
    }
    
    
    @Override
    public void launch(ILaunchConfiguration configuration, String mode, ILaunch launch, IProgressMonitor monitor) throws CoreException {
    	LaunchUtils.setProjectName(launch, configuration.getAttribute(LaunchUtils.ATTR_PROJECT_NAME, (String) null));
    	
    	Boolean activateAutoReload = CCWPlugin.isAutoReloadOnStartupSaveEnabled();
        launch.setAttribute(LaunchUtils.ATTR_IS_AUTO_RELOAD_ENABLED, Boolean.toString(activateAutoReload));
        
        BundleUtils.requireAndGetVar(CCWPlugin.getDefault().getBundle().getSymbolicName(), "clojure.tools.nrepl.ack/reset-ack-port!").invoke();
        try {
            Var.pushThreadBindings(RT.map(currentLaunch, launch));
            
            super.launch(configuration, mode, launch, (monitor == null || !isLaunchREPL(configuration)) ?
                    monitor : new REPLViewLaunchMonitor(monitor, launch, true));
            for(IProcess p: launch.getProcesses()) {
            	System.out.println("Launched process with command line: " + p.getAttribute(IProcess.ATTR_CMDLINE));
            }
        } finally {
            Var.popThreadBindings();
        }
    }
	
	@Override
	public String getVMArguments(ILaunchConfiguration configuration) throws CoreException {
	    String launchId = UUID.randomUUID().toString();
	    return String.format(" -D%s=%s %s",
	            LaunchUtils.SYSPROP_LAUNCH_ID,
	            launchId,
	            super.getVMArguments(configuration));
	}
	
	@Override
	public String getProgramArguments(ILaunchConfiguration configuration) throws CoreException {
		String superProgramArguments = super.getProgramArguments(configuration);
		if (isLeiningenConfiguration(configuration)) {
			List<IFile> filesToLaunch = LaunchUtils.getFilesToLaunchList(configuration);
			if (filesToLaunch.size() > 0) {
				int headlessReplOffset = superProgramArguments.indexOf("repl :headless");
				String arguments = superProgramArguments.substring(0, headlessReplOffset) +
						" " + createFileLoadInjections(filesToLaunch) +
						" -- " + superProgramArguments.substring(headlessReplOffset);
				return arguments;
			} else {
				return superProgramArguments;
			}
		}
		
		String userProgramArguments = superProgramArguments;

		if (isLaunchREPL(configuration)) {
			String filesToLaunchArguments = LaunchUtils.getFilesToLaunchAsCommandLineList(configuration, false);
			
			// TODO why don't we just add the ccw stuff to the classpath as we do for nrepl?
			String toolingFile = null;
			try {
				URL toolingFileURL = CCWPlugin.getDefault().getBundle().getResource("ccw/debug/serverrepl.clj");
				toolingFile = FileLocator.toFileURL(toolingFileURL).getFile();
			} catch (IOException e) {
				throw new WorkbenchException("Could not find ccw.debug.serverrepl source file", e);
			}
			
			String nREPLInit = "(require 'clojure.tools.nrepl.server)" + 
			    // don't want start-server return value printed
			    String.format("(do (clojure.tools.nrepl.server/start-server :ack-port %s) nil)", CCWPlugin.getDefault().getREPLServerPort());
			
			String args = String.format("-i \"%s\" -e \"%s\" %s %s", toolingFile, nREPLInit,
			        filesToLaunchArguments, userProgramArguments);
			
			CCWPlugin.log("Starting REPL with program args: " + args);
			return args;
		} else {
			String filesToLaunchArguments = LaunchUtils.getFilesToLaunchAsCommandLineList(configuration, true);
			
	    	return filesToLaunchArguments + " " + userProgramArguments;
		}
	}
	
	private String createFileLoadInjections(List<IFile> filesToLaunch) {
		
		assert filesToLaunch.size() > 0;
		
		StringBuilder sb = new StringBuilder();
		sb.append(" update-in :injections conj \"");
		for (IFile file: filesToLaunch) {
			// We use load so that the right info are compiled for use with breakpoints in a debugger
			String path = ClojureCore.getAsRootClasspathRelativePath(file);
			int offset = path.lastIndexOf(".clj");
			sb.append("(try (load \\\"" + path.substring(0, offset) + "\\\") (catch Exception e (.printStackTrace e)))");
		}
		sb.append("\" ");
		return sb.toString();
	}

	private static boolean isLaunchREPL(ILaunchConfiguration configuration) throws CoreException {
        return configuration.getAttribute(LaunchUtils.ATTR_CLOJURE_START_REPL, true);
    }
	
	public static boolean isLeiningenConfiguration(ILaunchConfiguration configuration) throws CoreException {
		return configuration.getAttribute(LaunchUtils.ATTR_LEININGEN_CONFIGURATION, false);
	}
	
	public static boolean isAutoReloadEnabled (ILaunch launch) {
		if (launch == null) {
			return false;
		} else {
			return Boolean.valueOf(launch.getAttribute(LaunchUtils.ATTR_IS_AUTO_RELOAD_ENABLED));
		}
	}

    @Override
	public String getMainTypeName(ILaunchConfiguration configuration)
			throws CoreException {
    	if (isLeiningenConfiguration(configuration)) {
    		// Leiningen configuration don't need last minute classpath tweaks (yet)
    		return super.getMainTypeName(configuration);
    	}
    	
	    String main = configuration.getAttribute(IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME, (String)null); 
	    return main == null ? clojure.main.class.getName() : main;
	}
	
    @Override
    public String[] getClasspath(ILaunchConfiguration configuration)
            throws CoreException {
    	
    	if (isLeiningenConfiguration(configuration)) {
    		// Leiningen configurations don't need last minute classpath tweaks (yet)
    		return super.getClasspath(configuration);
    	}
       
        List<String> classpath = new ArrayList<String>(Arrays.asList(super.getClasspath(configuration)));
       
        ClojureProject clojureProject = ClojureCore.getClojureProject(LaunchUtils.getProject(configuration));
        for (IFolder f: clojureProject.sourceFolders()) {
            String sourcePath = f.getLocation().toOSString();
           
            while (classpath.contains(sourcePath)) {
                // The sourcePath already exists, remove it first
                classpath.remove(sourcePath);
            }
           
            classpath.add(0, sourcePath);
        }
        
        
        if (clojureProject.getJavaProject().findElement(new Path("clojure/tools/nrepl")) == null) {
            try {
                File ccwPluginDir = FileLocator.getBundleFile(CCWPlugin.getDefault().getBundle());
                System.out.println("ccwPluginDir: " + ccwPluginDir);
                // this should *always* be a file, *unless* the user is getting nREPL from a clone of its
                // project, in which case we need to reach into that project's directory...
                ArrayList replAdditions = new ArrayList();
                if (ccwPluginDir.isFile()) {
                	throw new WorkbenchException("Bundle ccw.core is a file. Cannot install nrepl");
                } else {
                    //replAdditions.add(new File(repllib, "src/main/clojure").getAbsolutePath());
                    //replAdditions.add(new File(repllib, "target/classes").getAbsolutePath());
                	
                	// Hack, until the project is launched via leiningen instead
                	File nreplFile = new File(ccwPluginDir, "lib/tools.nrepl.jar");
					String nreplPath = nreplFile.getAbsolutePath();
					if (!nreplFile.exists()) {
						throw new WorkbenchException("nreplFile not found: " + nreplFile);
					}
                	System.out.println("nreplPath for classpath:" + nreplPath);
                	replAdditions.add(nreplPath);
                }
                
                CCWPlugin.log("Adding to project's classpath to support nREPL: " + replAdditions);
                
                classpath.addAll(replAdditions);
            } catch (IOException e) {
                throw new WorkbenchException("Failed to find nrepl library", e);
            }
        } else {
        	System.out.println("Found package clojure.tools.nrepl in the project classpath, won't try to add ccw's nrepl to it then");
        }
        
        return classpath.toArray(new String[classpath.size()]);
    }
}
