package gov.va.med.term.ucum.mojo;

import gov.va.oia.terminology.converters.sharedUtils.ConsoleUtil;
import gov.va.oia.terminology.converters.sharedUtils.EConceptUtility;
import gov.va.oia.terminology.converters.sharedUtils.EConceptUtility.DescriptionType;
import gov.va.oia.terminology.converters.sharedUtils.propertyTypes.BPT_Attributes;
import gov.va.oia.terminology.converters.sharedUtils.propertyTypes.BPT_ContentVersion;
import gov.va.oia.terminology.converters.sharedUtils.propertyTypes.BPT_Refsets;
import gov.va.oia.terminology.converters.sharedUtils.stats.ConverterUUID;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.FilenameFilter;
import java.io.IOException;
import java.text.ParsePosition;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.lang.StringUtils;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.dwfa.cement.ArchitectonicAuxiliary;
import org.eclipse.uomo.units.impl.format.LocalUnitFormatImpl;
import org.ihtsdo.etypes.EConcept;
import org.ihtsdo.tk.dto.concept.component.description.TkDescription;
import org.ihtsdo.tk.dto.concept.component.refex.type_string.TkRefsetStrMember;
import au.com.bytecode.opencsv.CSVWriter;

/**
 * Goal which converts CHDR data into the workbench jbin format
 * 
 * @goal process-ucum-data
 * 
 * @phase process-sources
 */
public class UCUMProcessorMojo extends AbstractMojo
{
	private static final String ucumNamespaceBaseSeed = "gov.va.med.term.ucum";

	private BPT_Refsets refsets;
	private BPT_ContentVersion contentVersion;
	private BPT_Attributes attributes;
	private EConceptUtility eConceptUtil_;
	private DataOutputStream dos;

	/**
	 * Where to put the output file.
	 * 
	 * @parameter expression="${project.build.directory}"
	 * @required
	 */
	private File outputDirectory;

	/**
	 * Location of source data file. Expected to be a directory.
	 * 
	 * @parameter
	 * @required
	 */
	private File inputFile;

	/**
	 * Input file details
	 * 
	 * @parameter
	 * @required
	 */
	private String artifactGroup;

	/**
	 * Input file details
	 * 
	 * @parameter
	 * @required
	 */
	private String artifactId;

	/**
	 * Input file details
	 * 
	 * @parameter
	 * @required
	 */
	private String artifactVersion;

	/**
	 * Input file details
	 * 
	 * @parameter
	 * @optional
	 */
	private String artifactClassifier;

	/**
	 * Loader version number Use parent because project.version pulls in the version of the data file, which I don't want.
	 * 
	 * @parameter expression="${project.parent.version}"
	 * @required
	 */
	private String loaderVersion;

	/**
	 * Content version number
	 * 
	 * @parameter expression="${project.version}"
	 * @required
	 */
	private String releaseVersion;

	public void execute() throws MojoExecutionException
	{
		try
		{
			if (!outputDirectory.exists())
			{
				outputDirectory.mkdirs();
			}

			File touch = new File(outputDirectory, "ucumEConcepts.jbin");
			dos = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(touch)));
			eConceptUtil_ = new EConceptUtility(ucumNamespaceBaseSeed, "UCUM Path", dos);

			refsets = new BPT_Refsets("UCUM");
			contentVersion = new BPT_ContentVersion();
			attributes = new BPT_Attributes();

			refsets.addProperty("has UCUM Unit");
			attributes.addProperty("UCUM Name");
			attributes.addProperty("UCUM Value");
			attributes.addProperty("UCUM Dimension");
			attributes.addProperty("UCUM Product Units");
			attributes.addProperty("UCUM Symbol");
			attributes.addProperty("UCUM System Units");

			EConcept metaDataRoot = eConceptUtil_.createConcept("UCUM Metadata", ArchitectonicAuxiliary.Concept.ARCHITECTONIC_ROOT_CONCEPT.getPrimoridalUid());
			metaDataRoot.writeExternal(dos);

			contentVersion.addProperty("artifactGroup");
			contentVersion.addProperty("artifactId");
			contentVersion.addProperty("artifactVersion");
			contentVersion.addProperty("artifactClassifier");

			eConceptUtil_.loadMetaDataItems(attributes, metaDataRoot.getPrimordialUuid(), dos);
			eConceptUtil_.loadMetaDataItems(contentVersion, metaDataRoot.getPrimordialUuid(), dos);
			eConceptUtil_.loadMetaDataItems(refsets, metaDataRoot.getPrimordialUuid(), dos);

			final EConcept refsetConcept = refsets.getConcept("has UCUM Unit");

			eConceptUtil_.addStringAnnotation(refsetConcept, artifactGroup, contentVersion.getProperty("artifactGroup").getUUID(), false);
			eConceptUtil_.addStringAnnotation(refsetConcept, artifactId, contentVersion.getProperty("artifactId").getUUID(), false);
			eConceptUtil_.addStringAnnotation(refsetConcept, artifactVersion, contentVersion.getProperty("artifactVersion").getUUID(), false);
			if (StringUtils.isNotEmpty(artifactClassifier))
			{
				eConceptUtil_.addStringAnnotation(refsetConcept, artifactClassifier, contentVersion.getProperty("artifactClassifier").getUUID(), false);
			}
			eConceptUtil_.addStringAnnotation(refsetConcept, releaseVersion, contentVersion.RELEASE.getUUID(), false);
			eConceptUtil_.addStringAnnotation(refsetConcept, loaderVersion, contentVersion.LOADER_VERSION.getUUID(), false);
			eConceptUtil_.addDescription(refsetConcept, "Unified Code for Units of Measure", DescriptionType.SYNONYM, true, null, null, false);

			ConsoleUtil.println("Metadata load stats");
			for (String line : eConceptUtil_.getLoadStats().getSummary())
			{
				ConsoleUtil.println(line);
			}
			eConceptUtil_.clearLoadStats();

			ConsoleUtil.println("Reading Input Concepts");

			final CSVWriter writer = new CSVWriter(new BufferedWriter(new FileWriter(new File(outputDirectory, "ucum.tsv"))), '\t');

			writer.writeNext(uomoParser("5", "mm").getColumnNames());

			int processed = 0;
			final AtomicInteger hitCounter = new AtomicInteger();
			;
			DataInputStream in = new DataInputStream(new FileInputStream(inputFile.listFiles(new FilenameFilter()
			{
				@Override
				public boolean accept(File dir, String name)
				{
					if (name.endsWith(".jbin"))
					{
						return true;
					}
					return false;
				}
			})[0]));

			int cpu = Runtime.getRuntime().availableProcessors() - 1;
			if (cpu < 1)
			{
				cpu = 1;
			}
			ExecutorService es = Executors.newFixedThreadPool(cpu);

			while (in.available() > 0)
			{
				processed++;
				if (processed % 1000 == 0)
				{
					ConsoleUtil.showProgress();
				}
				final EConcept concept = new EConcept(in);

				es.submit(new Runnable()
				{
					@Override
					public void run()
					{
						try
						{
							if (concept.getDescriptions() != null)
							{
								ArrayList<UCUMValue> ucumData = new ArrayList<>();

								for (TkDescription d : concept.getDescriptions())
								{
									ucumData.addAll(getUCUMValue(concept.getPrimordialUuid(), d.getText()));
								}
								if (ucumData.size() > 0)
								{
									synchronized (refsetConcept)
									{
										eConceptUtil_.addRefsetMember(refsetConcept, concept.getPrimordialUuid(), null, true, null);
									}
									EConcept skeletonToMerge = eConceptUtil_.createSkeletonClone(concept);
									hitCounter.incrementAndGet();

									HashMap<Integer, ArrayList<Integer>> uniqueForConcept = new HashMap<>();

									for (int pos = 0; pos < ucumData.size(); pos++)
									{
										writer.writeNext(ucumData.get(pos).getRowData());

										ArrayList<Integer> posList = uniqueForConcept.get(ucumData.get(pos).requiredDataHash());
										if (posList == null)
										{
											posList = new ArrayList<>();
											uniqueForConcept.put(ucumData.get(pos).requiredDataHash(), posList);
										}
										posList.add(pos);
									}

									for (ArrayList<Integer> positions : uniqueForConcept.values())
									{
										// Just use the first position for now. Might need the others later, if they want to track which description
										// it came from
										UCUMValue v = ucumData.get(positions.get(0));

										UUID annotationUUID = ConverterUUID.createNamespaceUUIDFromString(skeletonToMerge.getPrimordialUuid().toString() + ":"
												+ v.getUUIDComponents());

										TkRefsetStrMember ucumPrimary = eConceptUtil_.addStringAnnotation(skeletonToMerge.getConceptAttributes(), annotationUUID, v.name,
												attributes.getProperty("UCUM Name").getUUID(), false, null);

										eConceptUtil_.addStringAnnotation(ucumPrimary, v.value, attributes.getProperty("UCUM Value").getUUID(), false);
										if (v.dimension != null && v.dimension.length() > 0)
										{
											eConceptUtil_.addStringAnnotation(ucumPrimary, v.dimension, attributes.getProperty("UCUM Dimension").getUUID(), false);
										}
										if (v.productUnits != null && v.productUnits.length() > 0)
										{
											eConceptUtil_.addStringAnnotation(ucumPrimary, v.productUnits, attributes.getProperty("UCUM Product Units").getUUID(), false);
										}
										if (v.symbol != null && v.symbol.length() > 0)
										{
											eConceptUtil_.addStringAnnotation(ucumPrimary, v.symbol, attributes.getProperty("UCUM Symbol").getUUID(), false);
										}
										if (v.systemUnits != null && v.systemUnits.length() > 0)
										{
											eConceptUtil_.addStringAnnotation(ucumPrimary, v.systemUnits, attributes.getProperty("UCUM System Units").getUUID(), false);
										}
									}
									synchronized (dos)
									{
										skeletonToMerge.writeExternal(dos);
									}
								}
							}
						}
						catch (IOException e)
						{
							e.printStackTrace();
							System.exit(-1);
						}
					}
				});
			}
			in.close();

			es.shutdown();
			es.awaitTermination(24, TimeUnit.HOURS);

			ConsoleUtil.println("Processed " + processed + " concepts, identified UCUM data on " + hitCounter);
			writer.close();

			// And write out the refset concepts
			eConceptUtil_.storeRefsetConcepts(refsets, dos);

			dos.flush();
			dos.close();

			for (String line : eConceptUtil_.getLoadStats().getSummary())
			{
				ConsoleUtil.println(line);
			}

			// this could be removed from final release. Just added to help debug editor problems.
			ConsoleUtil.println("Dumping UUID Debug File");
			ConverterUUID.dump(new File(outputDirectory, "ucumUuidDebugMap.txt"));

			ConsoleUtil.writeOutputToFile(new File(outputDirectory, "ConsoleOutput.txt").toPath());
		}
		catch (Exception ex)
		{
			throw new MojoExecutionException(ex.getLocalizedMessage(), ex);
		}
	}

	private ArrayList<UCUMValue> getUCUMValue(UUID concept, String text)
	{
		ArrayList<UCUMValue> results = new ArrayList<>();

		Iterator<String> items = tokenize(text).iterator();

		String previous = null;
		String current = null;

		while (!isNumeric(previous) && items.hasNext())
		{
			previous = items.next();
		}
		if (!items.hasNext())
		{
			return results;
		}
		current = items.next();

		parseHelper(concept, text, previous, current, results);

		while (items.hasNext())
		{
			previous = current;
			current = items.next();

			while (!isNumeric(previous) && items.hasNext())
			{
				previous = current;
				current = items.next();
			}
			if (previous.equals(current) || !isNumeric(previous))
			{
				continue;
			}
			parseHelper(concept, text, previous, current, results);
		}
		return results;
	}

	private boolean isNumeric(String s)
	{
		if (s == null || s.length() == 0)
		{
			return false;
		}
		try
		{
			Float.parseFloat(s);
			return true;
		}
		catch (Exception e)
		{
			try
			{
				Double.parseDouble(s);
				return true;
			}
			catch (Exception e2)
			{
				try
				{
					Integer.parseInt(s);
					return true;
				}
				catch (Exception e3)
				{
					return false;
				}
			}
		}
	}

	private void parseHelper(UUID concept, String fullText, String previous, String current, ArrayList<UCUMValue> resultHolder)
	{
		if (previous == null || previous.length() == 0 || current == null || current.length() == 0 || current.equals("(") || current.equals(")"))
		{
			return;
		}
		
		if (current.equals("H"))
		{
			current = "h";  //They probably mean hour - not "Henry"
		}
		
		UCUMValue potential = uomoParser(previous, current);
		if (potential != null)
		{
			potential.concept = concept;
			potential.fullConceptDescription = fullText;
			resultHolder.add(potential);
		}
		UCUMValue potential2 = jScienceParser(previous, current);
		if (potential2 != null)
		{
			// jScience only provides these 3. Only load the second one if it differs from the one found by uomo
			if (potential == null
					|| !(potential.name.equals(potential2.name) || potential.dimension.equals(potential2.dimension) || potential.systemUnits
							.equals(potential2.systemUnits)))
			{
				potential2.concept = concept;
				potential2.fullConceptDescription = fullText;
				resultHolder.add(potential2);
			}
		}
	}

	private UCUMValue uomoParser(String value, String textToParse)
	{
		try
		{
			// Not sure if this is threadsafe, so no reuse
			LocalUnitFormatImpl uomoParser = new LocalUnitFormatImpl();
			org.unitsofmeasurement.unit.Unit<?> unit = uomoParser.parse(textToParse, new ParsePosition(0));
			if (unit != null)
			{
				return new UCUMValue(textToParse, value, unit);
			}
		}
		catch (Exception e)
		{
			// noop
		}
		return null;
	}

	private UCUMValue jScienceParser(String value, String textToParse)
	{
		try
		{
			// Not sure if this is threadsafe... so no reuse
			javax.measure.unit.UnitFormat jScienceParser = javax.measure.unit.UnitFormat.getUCUMInstance();
			javax.measure.unit.Unit<?> unit = jScienceParser.parseObject(textToParse, new ParsePosition(0));
			if (unit != null)
			{
				return new UCUMValue(textToParse, value, unit);
			}
		}
		catch (Exception e)
		{
			// noop
		}
		return null;
	}

	private class UCUMValue
	{
		String value, parsedText, name, symbol, dimension, productUnits, systemUnits;
		UUID concept;
		String fullConceptDescription;

		public UCUMValue(String parsedText, String value, javax.measure.unit.Unit<?> unit)
		{
			this.parsedText = parsedText;
			this.value = value;
			name = unit.toString();
			dimension = unit.getDimension().toString();
			systemUnits = unit.getStandardUnit().toString();
			check();
		}

		public UCUMValue(String parsedText, String value, org.unitsofmeasurement.unit.Unit<?> unit)
		{
			this.parsedText = parsedText;
			this.value = value;
			name = unit.toString();
			symbol = unit.getSymbol();
			dimension = unit.getDimension().toString();
			productUnits = unit.getDimension().toString();
			systemUnits = unit.getSystemUnit().toString();
			check();
		}

		private void check()
		{
			if (isNumeric(name))
			{
				throw new IllegalArgumentException();
			}
			if (name == null || name.length() == 0)
			{
				throw new IllegalArgumentException();
			}
			if (name.equals("%") || name.equals("'") || name.equals("\""))
			{
				throw new IllegalArgumentException();
			}
			if (name.equals("a") || name.equals("c")) //atto, speed of light...
			{
				throw new IllegalArgumentException();
			}
			if (name.equals("grade") || name.equals("K")) //odd one... , Kelvin - which diesn't get used as far as I can see
			{
				throw new IllegalArgumentException();
			}
			if (name.equals("rd")) //an incorrect abbreviation for rad, which is wrong in the cases I looked at
			{
				throw new IllegalArgumentException();
			}
			if (dimension != null && dimension.contains("I"))  //CURRENT
			{
				throw new IllegalArgumentException("Curent is unlikely in SCT");
			}
		}

		protected int requiredDataHash()
		{
			return (value + "|" + name + "|" + dimension + "|" + systemUnits).hashCode();
		}

		protected String getUUIDComponents()
		{
			return name + ":" + value + ":" + dimension + ":" + systemUnits;
		}

		public String[] getColumnNames()
		{
			return new String[] { "Value", "Parsed Text", "Name", "Symbol", "Dimension", "Product Units", "System Units", "Concept UUID", "Concept Text" };
		}

		public String[] getRowData()
		{
			return new String[] { (value == null ? "" : value), (parsedText == null ? "" : parsedText), (name == null ? "" : name), (symbol == null ? "" : symbol),
					(dimension == null ? "" : dimension), (productUnits == null ? "" : productUnits), (systemUnits == null ? "" : systemUnits),
					(concept == null ? "" : concept.toString()), (fullConceptDescription == null ? "" : fullConceptDescription) };
		}
	}
	
	private static ArrayList<String> tokenize(String string)
	{
		ArrayList<String> tokens = new ArrayList<>();
		
		if (string != null && string.length() > 0)
		{
			Iterator<String> whitespaceSplit = Arrays.asList(string.split("\\s")).iterator();
			while (whitespaceSplit.hasNext())
			{
				String temp = whitespaceSplit.next().trim();
				if (temp.length() == 0)
				{
					continue;
				}
				//subsplit on digits? - aka 'ab5.0mg' into 'ab' 5.0' 'mg'
				
				Boolean readingDigits = null;
				int digitStart = -1;
				int textStart = -1;
				for (int i = 0; i < temp.length(); i++)
				{
					if (Character.isDigit(temp.charAt(i)) || temp.charAt(i) == '.')
					{
						if (readingDigits == null)
						{
							readingDigits = true;
							digitStart = i;
						}
						else if (readingDigits)
						{
							continue;
						}
						else
						{
							readingDigits = true;
							digitStart = i;
							if (textStart != -1 && i > textStart)
							{
								tokens.add(temp.substring(textStart, digitStart));
							}
							textStart = -1;
						}
					}
					else
					{
						if (readingDigits == null)
						{
							readingDigits = false;
							textStart = i;
						}
						else if (readingDigits)
						{
							readingDigits = false;
							textStart = i;
							if (digitStart != -1 && i > digitStart)
							{
								tokens.add(temp.substring(digitStart, textStart));
							}
							digitStart = -1;
						}
						else
						{
							continue;
						}
					}
				}
				
				if (textStart != -1)
				{
					tokens.add(temp.substring(textStart));
				}
				if (digitStart != -1)
				{
					tokens.add(temp.substring(digitStart));
				}
			}
		}
		return tokens;
	}

	public static void main(String[] args) throws Exception
	{
		UCUMProcessorMojo i = new UCUMProcessorMojo();
		i.outputDirectory = new File("../ucum-econcept/target");
		i.inputFile = new File("../ucum-econcept/target/generated-resources/data");
		i.artifactClassifier = "a";
		i.artifactGroup = "b";
		i.artifactId = "c";
		i.artifactVersion = "d";
		i.execute();
	}
}