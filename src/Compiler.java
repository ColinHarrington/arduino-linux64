/* -*- mode: java; c-basic-offset: 2; indent-tabs-mode: nil -*- */

/*
  Part of the Processing project - http://processing.org

  Copyright (c) 2004-08 Ben Fry and Casey Reas
  Copyright (c) 2001-04 Massachusetts Institute of Technology

  This program is free software; you can redistribute it and/or modify
  it under the terms of the GNU General Public License as published by
  the Free Software Foundation; either version 2 of the License, or
  (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program; if not, write to the Free Software Foundation,
  Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/

package processing.app.debug;

import processing.app.Base;
import processing.app.Preferences;
import processing.app.Sketch;
import processing.app.SketchCode;
import processing.core.*;
import processing.app.I18n;
import processing.app.helpers.filefilters.OnlyDirs;
import static processing.app.I18n._;

import java.io.*;
import java.util.*;
import java.util.zip.*;


public class Compiler implements MessageConsumer {
  static final String BUGS_URL =
    _("http://github.com/arduino/Arduino/issues");
  static final String SUPER_BADNESS =
    I18n.format(_("Compiler error, please submit this code to {0}"), BUGS_URL);

  Sketch sketch;
  String buildPath;
  String primaryClassName;
  String gccCommand;
  String gppCommand;
  boolean verbose;
  boolean sketchIsCompiled;

  RunnerException exception;

  public Compiler() { }

  /**
   * Compile with avr-gcc.
   *
   * @param sketch Sketch object to be compiled.
   * @param buildPath Where the temporary files live and will be built from.
   * @param primaryClassName the name of the combined sketch file w/ extension
   * @return true if successful.
   * @throws RunnerException Only if there's a problem. Only then.
   */
  public boolean compile(Sketch sketch,
                         String buildPath,
                         String primaryClassName,
                         boolean verbose) throws RunnerException {
    this.sketch = sketch;
    this.buildPath = buildPath;
    this.primaryClassName = primaryClassName;
    this.verbose = verbose;
    this.sketchIsCompiled = false;

    // the pms object isn't used for anything but storage
    MessageStream pms = new MessageStream(this);

    String avrBasePath = Base.getAvrBasePath();
    Map<String, String> boardPreferences = Base.getBoardPreferences();
    gccCommand = boardPreferences.get("build.command.gcc");
    gppCommand = boardPreferences.get("build.command.g++");
    String arCommand = boardPreferences.get("build.command.ar");
    String objcopyCommand = boardPreferences.get("build.command.objcopy");
    if (gccCommand == null) gccCommand = "avr-gcc";
    if (gppCommand == null) gppCommand = "avr-g++";
    if (arCommand == null) arCommand = "avr-ar";
    if (objcopyCommand == null) objcopyCommand = "avr-objcopy";
    String core = boardPreferences.get("build.core");
    if (core == null) {
    	RunnerException re = new RunnerException(_("No board selected; please choose a board from the Tools > Board menu."));
      re.hideStackTrace();
      throw re;
    }
    String corePath;
    
    if (core.indexOf(':') == -1) {
      Target t = Base.getTarget();
      File coreFolder = new File(new File(t.getFolder(), "cores"), core);
      corePath = coreFolder.getAbsolutePath();
    } else {
      Target t = Base.targetsTable.get(core.substring(0, core.indexOf(':')));
      File coreFolder = new File(t.getFolder(), "cores");
      coreFolder = new File(coreFolder, core.substring(core.indexOf(':') + 1));
      corePath = coreFolder.getAbsolutePath();
    }

    String variant = boardPreferences.get("build.variant");
    String variantPath = null;
    
    if (variant != null) {
      if (variant.indexOf(':') == -1) {
	Target t = Base.getTarget();
	File variantFolder = new File(new File(t.getFolder(), "variants"), variant);
	variantPath = variantFolder.getAbsolutePath();
      } else {
	Target t = Base.targetsTable.get(variant.substring(0, variant.indexOf(':')));
	File variantFolder = new File(t.getFolder(), "variants");
	variantFolder = new File(variantFolder, variant.substring(variant.indexOf(':') + 1));
	variantPath = variantFolder.getAbsolutePath();
      }
    }

    List<File> objectFiles = new ArrayList<File>();

   // 0. include paths for core + all libraries

   sketch.setCompilingProgress(20);
   List includePaths = new ArrayList();
   includePaths.add(corePath);
   if (variantPath != null) includePaths.add(variantPath);
   for (File libFolder : sketch.getImportedLibraries()) {
     // Forward compatibility with 1.5 library format
     File propertiesFile = new File(libFolder, "library.properties");
     File srcFolder = new File(libFolder, "src");
     if (propertiesFile.isFile() && srcFolder.isDirectory())
       includePaths.add(srcFolder.getPath());
     else
       includePaths.add(libFolder.getPath());
   }

   // 1. compile the sketch (already in the buildPath)

   sketch.setCompilingProgress(30);
   objectFiles.addAll(
     compileFiles(avrBasePath, buildPath, includePaths,
               findFilesInPath(buildPath, "S", false),
               findFilesInPath(buildPath, "c", false),
               findFilesInPath(buildPath, "cpp", false),
               boardPreferences));
   sketchIsCompiled = true;

   // 2. compile the libraries, outputting .o files to: <buildPath>/<library>/

   sketch.setCompilingProgress(40);
   for (File libraryFolder : sketch.getImportedLibraries()) {
     File outputFolder = new File(buildPath, libraryFolder.getName());
     createFolder(outputFolder);
     
     // Forward compatibility with 1.5 library format
     File propertiesFile = new File(libraryFolder, "library.properties");
     File srcFolder = new File(libraryFolder, "src");
     if (propertiesFile.exists() && srcFolder.isDirectory()) {
       // Is an 1.5 library with "src" folder layout
       includePaths.add(srcFolder.getAbsolutePath());

       // Recursively compile "src" folder
        objectFiles.addAll(recursiveCompile(avrBasePath, srcFolder,
            outputFolder, includePaths, boardPreferences));
       
       includePaths.remove(includePaths.size() - 1);
       continue;
     }
     
     // Otherwise fallback to 1.0 library layout...
     
     File utilityFolder = new File(libraryFolder, "utility");
     // this library can use includes in its utility/ folder
     includePaths.add(utilityFolder.getAbsolutePath());
     objectFiles.addAll(
       compileFiles(avrBasePath, outputFolder.getAbsolutePath(), includePaths,
               findFilesInFolder(libraryFolder, "S", false),
               findFilesInFolder(libraryFolder, "c", false),
               findFilesInFolder(libraryFolder, "cpp", false),
               boardPreferences));
     outputFolder = new File(outputFolder, "utility");
     createFolder(outputFolder);
     objectFiles.addAll(
       compileFiles(avrBasePath, outputFolder.getAbsolutePath(), includePaths,
               findFilesInFolder(utilityFolder, "S", false),
               findFilesInFolder(utilityFolder, "c", false),
               findFilesInFolder(utilityFolder, "cpp", false),
               boardPreferences));
     // other libraries should not see this library's utility/ folder
     includePaths.remove(includePaths.size() - 1);
   }

    // 3. compile the core, outputting .o files to <buildPath> and then
    // collecting them into the core.a library file.

    sketch.setCompilingProgress(50);
    includePaths.clear();
    includePaths.add(corePath); // include path for core only
    if (variantPath != null)
      includePaths.add(variantPath);
    List<File> coreObjectFiles = compileFiles( //
        avrBasePath, buildPath, includePaths, //
        findFilesInPath(corePath, "S", true), //
        findFilesInPath(corePath, "c", true), //
        findFilesInPath(corePath, "cpp", true), //
        boardPreferences);

    if (variantPath != null)
      objectFiles.addAll(compileFiles( //
          avrBasePath, buildPath, includePaths, //
          findFilesInPath(variantPath, "S", true), //
          findFilesInPath(variantPath, "c", true), //
          findFilesInPath(variantPath, "cpp", true), //
          boardPreferences));

   String runtimeLibraryName = buildPath + File.separator + "core.a";
   List baseCommandAR = new ArrayList(Arrays.asList(new String[] {
     avrBasePath + arCommand,
     "rcs",
     runtimeLibraryName
   }));
   for(File file : coreObjectFiles) {
     List commandAR = new ArrayList(baseCommandAR);
     commandAR.add(file.getAbsolutePath());
     execAsynchronously(commandAR);
   }

    // 4. link it all together into the .elf file
    // For atmega2560, need --relax linker option to link larger
    // programs correctly.
    String optRelax = "";
    String atmega2560 = new String ("atmega2560");
    if ( atmega2560.equals(boardPreferences.get("build.mcu")) ) {
        optRelax = new String(",--relax");
    }
    if (Base.getBoardMenuPreferenceBoolean("build.linker_relaxation")) {
        optRelax = new String(",--relax");
    }
   sketch.setCompilingProgress(60);
    List baseCommandLinker = new ArrayList(Arrays.asList(new String[] {
      avrBasePath + gccCommand,
      "-Os",
      "-Wl,--gc-sections"+optRelax,
      (boardPreferences.get("build.cpu") != null) ?
        "-mcpu=" + boardPreferences.get("build.cpu") :
        "-mmcu=" + boardPreferences.get("build.mcu"),
    }));
    for (int i = 1; true; i++) {
      String extraOption = boardPreferences.get("build.linkoption" + i);
      if (extraOption == null) break;
      baseCommandLinker.add(extraOption);
    }
    String linkerScript = boardPreferences.get("build.linkscript");
    if (linkerScript != null) {
      baseCommandLinker.add("-T" + corePath + File.separator + linkerScript);
    }
    baseCommandLinker.add("-o");
    baseCommandLinker.add(buildPath + File.separator + primaryClassName + ".elf");

    for (File file : objectFiles) {
      baseCommandLinker.add(file.getAbsolutePath());
    }

    if (boardPreferences.get("build.noarchive") == null) {
      baseCommandLinker.add(runtimeLibraryName);
    } else {
      for(File file : coreObjectFiles) {
        // baseCommandLinker.add(file.getAbsolutePath());
        baseCommandLinker.add(file.toString());
      }
    }
    baseCommandLinker.add("-L" + buildPath);
    for (int i = 1; true; i++) {
      String additionalObject = boardPreferences.get("build.additionalobject" + i);
      if (additionalObject == null) break;
      baseCommandLinker.add(additionalObject);
    }
    baseCommandLinker.add("-lm");

    execAsynchronously(baseCommandLinker);

    // 4b. run a command to add data to the .elf file
    String elfAddCommand = Base.getBoardMenuPreference("build.elfpatch");
    if (elfAddCommand != null) {
      String scriptBasePath = Base.getHardwarePath() + "/tools/";
      List elfPatcher = new ArrayList();
      elfPatcher.add(scriptBasePath + elfAddCommand);
      //elfPatcher.add("-v");
      elfPatcher.add("-mmcu=" + boardPreferences.get("build.mcu"));
      elfPatcher.add(buildPath + File.separator + primaryClassName + ".elf");
      elfPatcher.add(sketch.getFolder() + File.separator + "disk");
      execAsynchronously(elfPatcher);
    }

    List baseCommandObjcopy = new ArrayList(Arrays.asList(new String[] {
      avrBasePath + objcopyCommand,
      "-O",
      "-R",
    }));
    
    List commandObjcopy;

    // 5. extract EEPROM data (from EEMEM directive) to .eep file.
    sketch.setCompilingProgress(70);
    commandObjcopy = new ArrayList(baseCommandObjcopy);
    commandObjcopy.add(2, "ihex");
    commandObjcopy.set(3, "-j");
    commandObjcopy.add(".eeprom");
    commandObjcopy.add("--set-section-flags=.eeprom=alloc,load");
    commandObjcopy.add("--no-change-warnings");
    commandObjcopy.add("--change-section-lma");
    commandObjcopy.add(".eeprom=0");
    commandObjcopy.add(buildPath + File.separator + primaryClassName + ".elf");
    commandObjcopy.add(buildPath + File.separator + primaryClassName + ".eep");
    execAsynchronously(commandObjcopy);
    
    // 6. build the .hex file
    sketch.setCompilingProgress(80);
    commandObjcopy = new ArrayList(baseCommandObjcopy);
    commandObjcopy.add(2, "ihex");
    commandObjcopy.add(".eeprom"); // remove eeprom data
    commandObjcopy.add(buildPath + File.separator + primaryClassName + ".elf");
    commandObjcopy.add(buildPath + File.separator + primaryClassName + ".hex");
    execAsynchronously(commandObjcopy);
    
    sketch.setCompilingProgress(90);
   
    // 7. run a post comile script, to alert external tools to new files
    String postCompileScript = boardPreferences.get("build.post_compile_script");
    if (postCompileScript != null) {
      String scriptBasePath = Base.getHardwarePath() + "/tools/";
      List commandScript = new ArrayList();
      commandScript.add(scriptBasePath + postCompileScript);
      commandScript.add("-board=" + Preferences.get("board"));
      commandScript.add("-tools=" + scriptBasePath);
      commandScript.add("-path=" + buildPath);
      commandScript.add("-file=" + primaryClassName);
      execTeensySimple(commandScript);
    }
    return true;
  }

  private List<File> recursiveCompile(String avrBasePath, File srcFolder,
      File outputFolder, List<File> includePaths,
      Map<String, String> boardPreferences) throws RunnerException {
    List<File> objectFiles = new ArrayList<File>();
    objectFiles.addAll(compileFiles(avrBasePath, outputFolder.getAbsolutePath(), includePaths,
        findFilesInFolder(srcFolder, "S", false),
        findFilesInFolder(srcFolder, "c", false),
        findFilesInFolder(srcFolder, "cpp", false),
        boardPreferences));

    // Recursively compile sub-folders
    for (File srcSubfolder : srcFolder.listFiles(new OnlyDirs())) {
      File outputSubfolder = new File(outputFolder, srcSubfolder.getName());
      createFolder(outputSubfolder);
      objectFiles.addAll(recursiveCompile(avrBasePath, srcSubfolder,
          outputSubfolder, includePaths, boardPreferences));
    }

    return objectFiles;
  }

  private List<File> compileFiles(String avrBasePath,
                                  String buildPath, List<File> includePaths,
                                  List<File> sSources, 
                                  List<File> cSources, List<File> cppSources,
                                  Map<String, String> boardPreferences)
    throws RunnerException {

    List<File> objectPaths = new ArrayList<File>();
    
    for (File file : sSources) {
      String objectPath = buildPath + File.separator + file.getName() + ".o";
      objectPaths.add(new File(objectPath));
      execAsynchronously(getCommandCompilerS(avrBasePath, gccCommand, includePaths,
                                             file.getAbsolutePath(),
                                             objectPath,
                                             boardPreferences));
    }
 		
    for (File file : cSources) {
        String objectPath = buildPath + File.separator + file.getName() + ".o";
        String dependPath = buildPath + File.separator + file.getName() + ".d";
        File objectFile = new File(objectPath);
        File dependFile = new File(dependPath);
        objectPaths.add(objectFile);
        if (is_already_compiled(file, objectFile, dependFile, boardPreferences)) continue;
        execAsynchronously(getCommandCompilerC(avrBasePath, gccCommand, includePaths,
                                               file.getAbsolutePath(),
                                               objectPath,
                                               boardPreferences));
    }

    for (File file : cppSources) {
        String objectPath = buildPath + File.separator + file.getName() + ".o";
        String dependPath = buildPath + File.separator + file.getName() + ".d";
        File objectFile = new File(objectPath);
        File dependFile = new File(dependPath);
        objectPaths.add(objectFile);
        if (is_already_compiled(file, objectFile, dependFile, boardPreferences)) continue;
        execAsynchronously(getCommandCompilerCPP(avrBasePath, gppCommand, includePaths,
                                                 file.getAbsolutePath(),
                                                 objectPath,
                                                 boardPreferences));
    }
    
    return objectPaths;
  }

  private boolean is_already_compiled(File src, File obj, File dep, Map<String, String> prefs) {
    boolean ret=true;
    try {
      //System.out.println("\n  is_already_compiled: begin checks: " + obj.getPath());
      if (src.getName().equals("mk20dx128.c")) return false; // ugly hack for TIME_T always compiled
      if (!obj.exists()) return false;  // object file (.o) does not exist
      if (!dep.exists()) return false;  // dep file (.d) does not exist
      long src_modified = src.lastModified();
      long obj_modified = obj.lastModified();
      if (src_modified >= obj_modified) return false;  // source modified since object compiled
      if (src_modified >= dep.lastModified()) return false;  // src modified since dep compiled
      BufferedReader reader = new BufferedReader(new FileReader(dep.getPath()));
      String line;
      boolean need_obj_parse = true;
      while ((line = reader.readLine()) != null) {
        if (line.endsWith("\\")) {
          line = line.substring(0, line.length() - 1);
        }
        line = line.trim();
        line = line.replaceAll("\\\\ ", " ");
        if (line.length() == 0) continue; // ignore blank lines
        if (need_obj_parse) {
          // line is supposed to be the object file - make sure it really is!
          if (line.endsWith(":")) {
            line = line.substring(0, line.length() - 1);
            String objpath = obj.getCanonicalPath();
            File linefile = new File(line);
            String linepath = linefile.getCanonicalPath();
            //System.out.println("  is_already_compiled: obj =  " + objpath);
            //System.out.println("  is_already_compiled: line = " + linepath);
            if (objpath.compareTo(linepath) == 0) {
              need_obj_parse = false;
              continue;
            } else {
              ret = false;  // object named inside .d file is not the correct file!
              break;
            }
          } else {
            ret = false;  // object file supposed to end with ':', but didn't
            break;
          }
        } else {
          // line is a prerequisite file
          File prereq = new File(line);
          if (!prereq.exists()) {
            ret = false;  // prerequisite file did not exist
            break;
          }
          if (prereq.lastModified() >= obj_modified) {
            ret = false;  // prerequisite modified since object was compiled
            break;
          }
          //System.out.println("  is_already_compiled:  prerequisite ok");
        }
      }
      reader.close();
    } catch (Exception e) {
      return false;  // any error reading dep file = recompile it
    }
    if (ret && (verbose || Preferences.getBoolean("build.verbose"))) {
      System.out.println("  Using previously compiled: " + obj.getPath());
    }
    return ret;
  }

  boolean firstErrorFound;
  boolean secondErrorFound;

  /**
   * Either succeeds or throws a RunnerException fit for public consumption.
   */
  private void execAsynchronously(List commandList) throws RunnerException {
    String[] command = new String[commandList.size()];
    commandList.toArray(command);
    int result = 0;
    
    if (verbose || Preferences.getBoolean("build.verbose")) {
      for(int j = 0; j < command.length; j++) {
        System.out.print(command[j] + " ");
      }
      System.out.println();
    }

    firstErrorFound = false;  // haven't found any errors yet
    secondErrorFound = false;

    Process process;
    
    try {
      process = Runtime.getRuntime().exec(command);
    } catch (IOException e) {
      RunnerException re = new RunnerException(e.getMessage());
      re.hideStackTrace();
      throw re;
    }

    MessageSiphon in = new MessageSiphon(process.getInputStream(), this);
    MessageSiphon err = new MessageSiphon(process.getErrorStream(), this);

    // wait for the process to finish.  if interrupted
    // before waitFor returns, continue waiting
    boolean compiling = true;
    while (compiling) {
      try {
        in.join();
        err.join();
        result = process.waitFor();
        //System.out.println("result is " + result);
        compiling = false;
      } catch (InterruptedException ignored) { }
    }

    // an error was queued up by message(), barf this back to compile(),
    // which will barf it back to Editor. if you're having trouble
    // discerning the imagery, consider how cows regurgitate their food
    // to digest it, and the fact that they have five stomaches.
    //
    //System.out.println("throwing up " + exception);
    if (exception != null) { throw exception; }

    if (result > 1) {
      // a failure in the tool (e.g. unable to locate a sub-executable)
      System.err.println(
	  I18n.format(_("{0} returned {1}"), command[0], result));
    }

    if (result != 0) {
      RunnerException re = new RunnerException(_("Error compiling."));
      re.hideStackTrace();
      throw re;
    }
  }

  private void execTeensySimple(List commandList) throws RunnerException {
    String[] command = new String[commandList.size()];
    commandList.toArray(command);
    Process process;
    int result = 0;
    try {
      process = Runtime.getRuntime().exec(command);
    } catch (IOException e) {
      RunnerException re = new RunnerException(e.getMessage());
      re.hideStackTrace();
      throw re;
    }
    MessageSiphon in = new MessageSiphon(process.getInputStream(), this);
    boolean running = true;
    while (running) {
      try {
        result = process.waitFor();
        running = false;
      } catch (InterruptedException ignored) { }
    }
    if (result != 0) {
      RunnerException re = new RunnerException("Error communicating with Teensy Loader");
      re.hideStackTrace();
      throw re;
    }
  }

  /**
   * Part of the MessageConsumer interface, this is called
   * whenever a piece (usually a line) of error message is spewed
   * out from the compiler. The errors are parsed for their contents
   * and line number, which is then reported back to Editor.
   */
  public void message(String s) {
    int i;

    // remove the build path so people only see the filename
    // can't use replaceAll() because the path may have characters in it which
    // have meaning in a regular expression.
    if (!verbose) {
      while ((i = s.indexOf(buildPath + File.separator)) != -1) {
        s = s.substring(0, i) + s.substring(i + (buildPath + File.separator).length());
      }
    }
  
    // look for error line, which contains file name, line number,
    // and at least the first line of the error message
    String errorFormat = "([\\w\\d_]+\\.\\w+):(\\d+):\\s*error:\\s*(.*)\\s*";
    String[] pieces = PApplet.match(s, errorFormat);
    if (pieces == null) {
      errorFormat = "([\\w\\d_]+\\.\\w+):(\\d+):\\d+:\\s*error:\\s*(.*)\\s*";
      pieces = PApplet.match(s, errorFormat);
    }

//    if (pieces != null && exception == null) {
//      exception = sketch.placeException(pieces[3], pieces[1], PApplet.parseInt(pieces[2]) - 1);
//      if (exception != null) exception.hideStackTrace();
//    }
    
    if (pieces != null) {
      String error = pieces[3], msg = "";
      
      if (pieces[3].trim().equals("SPI.h: No such file or directory")) {
        error = _("Please import the SPI library from the Sketch > Import Library menu.");
        msg = _("\nAs of Arduino 0019, the Ethernet library depends on the SPI library." +
              "\nYou appear to be using it or another library that depends on the SPI library.\n\n");
      }
      
      if (pieces[3].trim().equals("'BYTE' was not declared in this scope")) {
        error = _("The 'BYTE' keyword is no longer supported.");
        msg = _("\nAs of Arduino 1.0, the 'BYTE' keyword is no longer supported." +
              "\nPlease use Serial.write() instead.\n\n");
      }
      
      if (pieces[3].trim().equals("no matching function for call to 'Server::Server(int)'")) {
        error = _("The Server class has been renamed EthernetServer.");
        msg = _("\nAs of Arduino 1.0, the Server class in the Ethernet library " +
              "has been renamed to EthernetServer.\n\n");
      }
      
      if (pieces[3].trim().equals("no matching function for call to 'Client::Client(byte [4], int)'")) {
        error = _("The Client class has been renamed EthernetClient.");
        msg = _("\nAs of Arduino 1.0, the Client class in the Ethernet library " +
              "has been renamed to EthernetClient.\n\n");
      }
      
      if (pieces[3].trim().equals("'Udp' was not declared in this scope")) {
        error = _("The Udp class has been renamed EthernetUdp.");
        msg = _("\nAs of Arduino 1.0, the Udp class in the Ethernet library " +
              "has been renamed to EthernetUdp.\n\n");
      }
      
      if (pieces[3].trim().equals("'class TwoWire' has no member named 'send'")) {
        error = _("Wire.send() has been renamed Wire.write().");
        msg = _("\nAs of Arduino 1.0, the Wire.send() function was renamed " +
              "to Wire.write() for consistency with other libraries.\n\n");
      }
      
      if (pieces[3].trim().equals("'class TwoWire' has no member named 'receive'")) {
        error = _("Wire.receive() has been renamed Wire.read().");
        msg = _("\nAs of Arduino 1.0, the Wire.receive() function was renamed " +
              "to Wire.read() for consistency with other libraries.\n\n");
      }

      if (pieces[3].trim().equals("'Mouse' was not declared in this scope")) {
        error = _("'Mouse' only supported on the Arduino Leonardo");
        //msg = _("\nThe 'Mouse' class is only supported on the Arduino Leonardo.\n\n");
      }
      
      if (pieces[3].trim().equals("'Keyboard' was not declared in this scope")) {
        error = _("'Keyboard' only supported on the Arduino Leonardo");
        //msg = _("\nThe 'Keyboard' class is only supported on the Arduino Leonardo.\n\n");
      }

      if (Base.getBoardPreferences().get("build.core").equals("teensy")) {
        if (pieces[3].trim().equals("'Keyboard' was not declared in this scope")
	 || pieces[3].trim().equals("‘Keyboard’ was not declared in this scope"))
	  msg = "\nTo make a USB Keyboard, please select Keyboard from the Tools -> USB Type menu\n\n";
        if (pieces[3].trim().equals("'Mouse' was not declared in this scope")
	 || pieces[3].trim().equals("‘Mouse’ was not declared in this scope"))
	  msg = "\nTo make a USB Mouse, please select Mouse from the Tools -> USB Type menu\n\n";
        if (pieces[3].trim().equals("'Joystick' was not declared in this scope")
	 || pieces[3].trim().equals("‘Joystick’ was not declared in this scope"))
	  msg = "\nTo make a USB Joystick, please select Joystick from the Tools -> USB Type menu\n\n";
        if (pieces[3].trim().equals("'Disk' was not declared in this scope")
	 || pieces[3].trim().equals("‘Disk’ was not declared in this scope"))
	  msg = "\nTo make a USB Disk, please select Disk from the Tools -> USB Type menu\n\n";
        if (pieces[3].trim().equals("'usbMIDI' was not declared in this scope")
	 || pieces[3].trim().equals("‘usbMIDI’ was not declared in this scope"))
	  msg = "\nTo make a USB MIDI device, please select MIDI from the Tools -> USB Type menu\n\n";
        if (pieces[3].trim().equals("'RawHID' was not declared in this scope")
	 || pieces[3].trim().equals("‘RawHID’ was not declared in this scope"))
	  msg = "\nTo make a RawHID device, please select RawHID from the Tools -> USB Type menu\n\n";
        if (pieces[3].trim().equals("'FlightSimCommand' does not name a type")
	 || pieces[3].trim().equals("‘FlightSimCommand’ does not name a type")
         || pieces[3].trim().equals("'FlightSimInteger' does not name a type")
	 || pieces[3].trim().equals("‘FlightSimInteger’ does not name a type")
         || pieces[3].trim().equals("'FlightSimFloat' does not name a type")
	 || pieces[3].trim().equals("‘FlightSimFloat’ does not name a type")
         || pieces[3].trim().equals("'FlightSim' was not declared in this scope")
	 || pieces[3].trim().equals("‘FlightSim’ was not declared in this scope"))
	  msg = "\nTo make a Flight Simulator device, please select Flight Sim Controls from the Tools -> USB Type menu\n\n";
      }
      
      RunnerException e = null;
      if (!sketchIsCompiled) {
        // Place errors when compiling the sketch, but never while compiling libraries
        // or the core.  The user's sketch might contain the same filename!
        e = sketch.placeException(error, pieces[1], PApplet.parseInt(pieces[2]) - 1);
      }

      // replace full file path with the name of the sketch tab (unless we're
      // in verbose mode, in which case don't modify the compiler output)
      if (e != null && !verbose) {
        SketchCode code = sketch.getCode(e.getCodeIndex());
        String fileName = (code.isExtension("ino") || code.isExtension("pde")) ? code.getPrettyName() : code.getFileName();
        int lineNum = e.getCodeLine() + 1;
        s = fileName + ":" + lineNum + ": error: " + pieces[3] + msg;        
      }
            
      if (exception == null && e != null) {
        exception = e;
        exception.hideStackTrace();
      }      
    }
    
		if (s.contains("undefined reference to `SPIClass::begin()'")
				&& s.contains("libraries/Robot_Control")) {
			String error = _("Please import the SPI library from the Sketch > Import Library menu.");
			exception = new RunnerException(error);
		}
    
		if (s.contains("undefined reference to `Wire'")
				&& s.contains("libraries/Robot_Control")) {
			String error = _("Please import the Wire library from the Sketch > Import Library menu.");
			exception = new RunnerException(error);
		}
		
    System.err.print(s);
  }

  /////////////////////////////////////////////////////////////////////////////

  static private void menu_defines(List cmd, Map<String, String> boardPrefs) {
    for (int i=0; i<10; i++) {
      String define = Base.getBoardMenuPreference("build.define" + i);
      if (define != null) cmd.add(define);
    }
  }
  static private void serial_number_define(List cmd, Map<String, String> boardPrefs) {
    if (Base.getBoardMenuPreferenceBoolean("build.serial_number")) {
      Random r = new Random();
      cmd.add("-DSERIALNUM=" + r.nextInt());
    }
  }

  static private List getCommandCompilerS(String avrBasePath, String gccCmd, List includePaths,
    String sourceName, String objectName, Map<String, String> boardPreferences) {
    List baseCommandCompiler = new ArrayList(Arrays.asList(new String[] {
      avrBasePath + gccCmd,
      "-c", // compile, don't link
      "-g", // include debugging info (so errors include line numbers)
      "-x","assembler-with-cpp",
      (boardPreferences.get("build.cpu") != null) ?
        "-mcpu=" + boardPreferences.get("build.cpu") :
        "-mmcu=" + boardPreferences.get("build.mcu"),
      "-DF_CPU=" + Base.getBoardMenuPreference("build.f_cpu"),
      "-DARDUINO=" + Base.REVISION,
      "-DUSB_VID=" + boardPreferences.get("build.vid"),
      "-DUSB_PID=" + boardPreferences.get("build.pid"),
    }));
    for (int i = 1; true; i++) {
      String extraOption = boardPreferences.get("build.option" + i);
      if (extraOption == null) break;
      baseCommandCompiler.add(extraOption);
    }
    menu_defines(baseCommandCompiler, boardPreferences);

    for (int i = 0; i < includePaths.size(); i++) {
      baseCommandCompiler.add("-I" + (String) includePaths.get(i));
    }

    baseCommandCompiler.add(sourceName);
    baseCommandCompiler.add("-o"+ objectName);

    return baseCommandCompiler;
  }

  
  static private List getCommandCompilerC(String avrBasePath, String gccCmd, List includePaths,
    String sourceName, String objectName, Map<String, String> boardPreferences) {

    List baseCommandCompiler = new ArrayList(Arrays.asList(new String[] {
      avrBasePath + gccCmd,
      "-c", // compile, don't link
      "-g", // include debugging info (so errors include line numbers)
      "-Os", // optimize for size
      Preferences.getBoolean("build.verbose") ? "-Wall" : "-w", // show warnings if verbose
      "-ffunction-sections", // place each function in its own section
      "-fdata-sections",
      (boardPreferences.get("build.cpu") != null) ?
        "-mcpu=" + boardPreferences.get("build.cpu") :
        "-mmcu=" + boardPreferences.get("build.mcu"),
      "-DF_CPU=" + Base.getBoardMenuPreference("build.f_cpu"),
      "-MMD", // output dependancy info
      "-DUSB_VID=" + boardPreferences.get("build.vid"),
      "-DUSB_PID=" + boardPreferences.get("build.pid"),
      "-DARDUINO=" + Base.REVISION, 
    }));
    for (int i = 1; true; i++) {
      String extraOption = boardPreferences.get("build.option" + i);
      if (extraOption == null) break;
      baseCommandCompiler.add(extraOption);
    }
    if (boardPreferences.get("build.thumb") != null) {
      baseCommandCompiler.add("-mthumb");
    }
    if (boardPreferences.get("build.time_t") != null) {
      Date d = new Date();
      Calendar cal = new GregorianCalendar();
      long current = d.getTime()/1000;
      long timezone = cal.get(cal.ZONE_OFFSET)/1000;
      long daylight = cal.get(cal.DST_OFFSET)/1000;
      long time_t_value = current + timezone + daylight;
      baseCommandCompiler.add("-DTIME_T=" + time_t_value);
    }
    menu_defines(baseCommandCompiler, boardPreferences);
    serial_number_define(baseCommandCompiler, boardPreferences);
		
    for (int i = 0; i < includePaths.size(); i++) {
      baseCommandCompiler.add("-I" + (String) includePaths.get(i));
    }

    baseCommandCompiler.add(sourceName);
    baseCommandCompiler.add("-o");
    baseCommandCompiler.add(objectName);

    return baseCommandCompiler;
  }
	
	
  static private List getCommandCompilerCPP(String avrBasePath, String gppCmd,
    List includePaths, String sourceName, String objectName,
    Map<String, String> boardPreferences) {
    
    List baseCommandCompilerCPP = new ArrayList(Arrays.asList(new String[] {
      avrBasePath + gppCmd,
      "-c", // compile, don't link
      "-g", // include debugging info (so errors include line numbers)
      "-Os", // optimize for size
      Preferences.getBoolean("build.verbose") ? "-Wall" : "-w", // show warnings if verbose
      "-fno-exceptions",
      "-ffunction-sections", // place each function in its own section
      "-fdata-sections",
      (boardPreferences.get("build.cpu") != null) ?
        "-mcpu=" + boardPreferences.get("build.cpu") :
        "-mmcu=" + boardPreferences.get("build.mcu"),
      "-DF_CPU=" + Base.getBoardMenuPreference("build.f_cpu"),
      "-MMD", // output dependancy info
      "-DUSB_VID=" + boardPreferences.get("build.vid"),
      "-DUSB_PID=" + boardPreferences.get("build.pid"),      
      "-DARDUINO=" + Base.REVISION,
    }));
    for (int i = 1; true; i++) {
      String extraOption = boardPreferences.get("build.option" + i);
      if (extraOption == null) break;
      baseCommandCompilerCPP.add(extraOption);
    }
    for (int i = 1; true; i++) {
      String extraOption = boardPreferences.get("build.cppoption" + i);
      if (extraOption == null) break;
      baseCommandCompilerCPP.add(extraOption);
    }
    if (Base.getBoardMenuPreferenceBoolean("build.elide_constructors"))
      baseCommandCompilerCPP.add("-felide-constructors");  // optimization used by string class
    if (Base.getBoardMenuPreferenceBoolean("build.cpp0x"))
      baseCommandCompilerCPP.add("-std=c++0x");  // rvalue ref feature used by string class
    if (Base.getBoardMenuPreferenceBoolean("build.gnu0x"))
      baseCommandCompilerCPP.add("-std=gnu++0x");  // rvalue ref feature used by string class
    menu_defines(baseCommandCompilerCPP, boardPreferences);

    for (int i = 0; i < includePaths.size(); i++) {
      baseCommandCompilerCPP.add("-I" + (String) includePaths.get(i));
    }

    baseCommandCompilerCPP.add(sourceName);
    baseCommandCompilerCPP.add("-o");
    baseCommandCompilerCPP.add(objectName);

    return baseCommandCompilerCPP;
  }



  /////////////////////////////////////////////////////////////////////////////

  static private void createFolder(File folder) throws RunnerException {
    if (folder.isDirectory()) return;
    if (!folder.mkdir())
      throw new RunnerException("Couldn't create: " + folder);
  }

  /**
   * Given a folder, return a list of the header files in that folder (but
   * not the header files in its sub-folders, as those should be included from
   * within the header files at the top-level).
   */
  static public String[] headerListFromIncludePath(String path) throws IOException {
    FilenameFilter onlyHFiles = new FilenameFilter() {
      public boolean accept(File dir, String name) {
        return name.endsWith(".h");
      }
    };
    File libFolder = new File(path);

    // Forward compatibility with 1.5 library format
    File propertiesFile = new File(libFolder, "library.properties");
    File srcFolder = new File(libFolder, "src");
    String[] list;
    if (propertiesFile.isFile() && srcFolder.isDirectory()) {
      // Is an 1.5 library with "src" folder
      list = srcFolder.list(onlyHFiles);
    } else {
      // Fallback to 1.0 library layout
      list = libFolder.list(onlyHFiles);
    }
    if (list == null) {
      throw new IOException();
    }
    return list;
  }
  
  static public ArrayList<File> findFilesInPath(String path, String extension,
                                                boolean recurse) {
    return findFilesInFolder(new File(path), extension, recurse);
  }
  
  static public ArrayList<File> findFilesInFolder(File folder, String extension,
                                                  boolean recurse) {
    ArrayList<File> files = new ArrayList<File>();
    
    if (folder.listFiles() == null) return files;
    
    for (File file : folder.listFiles()) {
      if (file.getName().startsWith(".")) continue; // skip hidden files
      
      if (file.getName().endsWith("." + extension))
        files.add(file);
        
      if (recurse && file.isDirectory()) {
        files.addAll(findFilesInFolder(file, extension, true));
      }
    }
    
    return files;
  }
}
