// Copyright (c) 2014, Clark & Parsia, LLC. <http://www.clarkparsia.com>

package com.complexible.common.csv;

import io.airlift.command.Arguments;
import io.airlift.command.Cli;
import io.airlift.command.Command;
import io.airlift.command.Help;
import io.airlift.command.Option;

import java.io.File;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openrdf.model.BNode;
import org.openrdf.model.Literal;
import org.openrdf.model.Resource;
import org.openrdf.model.Statement;
import org.openrdf.model.URI;
import org.openrdf.model.Value;
import org.openrdf.model.ValueFactory;
import org.openrdf.model.impl.ValueFactoryImpl;
import org.openrdf.rio.ParserConfig;
import org.openrdf.rio.RDFFormat;
import org.openrdf.rio.RDFHandler;
import org.openrdf.rio.RDFHandlerException;
import org.openrdf.rio.RDFParser;
import org.openrdf.rio.RDFWriter;
import org.openrdf.rio.Rio;
import org.openrdf.rio.RioSetting;
import org.openrdf.rio.helpers.BasicParserSettings;
import org.openrdf.rio.helpers.RDFHandlerBase;

import au.com.bytecode.opencsv.CSVReader;

import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import com.google.common.io.BaseEncoding;
import com.google.common.io.Files;

/**
 * Converts a CSV file to RDF based on a given template
 * 
 * @author Evren Sirin
 */
@Command(name = "convert", description = "Runs the conversion.")
public class CSV2RDF implements Runnable {
	private static final Charset INPUT_CHARSET = Charset.defaultCharset();
	private static final Charset OUTPUT_CHARSET = Charsets.UTF_8;
	private static final ValueFactory FACTORY = ValueFactoryImpl.getInstance();

	@Option(name = "--no-header", arity = 0, description = "If csv file does not contain a header row")
	boolean noHeader = false;

	@Option(name = { "-s", "--separator" }, description = "Seperator character used in the csv file or ',' by default.")
	String separator = String.valueOf(CSVReader.DEFAULT_SEPARATOR);

	@Option(name = { "-q", "--quote" }, description = "Quote character used in the csv file or '\"' by default.")
	String quote = String.valueOf(CSVReader.DEFAULT_QUOTE_CHARACTER);

	@Option(name = { "-e", "--escape" }, description = "Escape character used in the csv file or '\\' by default.")
	String escape = String.valueOf(CSVReader.DEFAULT_ESCAPE_CHARACTER);

	@Arguments(required = true, description = "File arguments. The extension of template file and output file determines the RDF format that will be used for them (.ttl = Turtle, .nt = N-Triples, .rdf = RDF/XML)", title = {
	                "templateFile", "csvFile", "outputFile" })
	public List<String> files;
	private int inputRows = 0;
	private int outputTriples = 0;

	public void run() {
		Preconditions.checkArgument(files.size() >= 3, "Missing arguments");
		Preconditions.checkArgument(files.size() <= 3, "Too many arguments");

		File templateFile = new File(files.get(0));
		File inputFile = new File(files.get(1));
		File outputFile =  new File(files.get(2));
		System.out.println("CSV to RDF conversion started...");
		System.out.println("Template: " + templateFile);
		System.out.println("Input   : " + inputFile);
		System.out.println("Output  : " + outputFile);
		
		try {
			Reader in = Files.newReader(inputFile, INPUT_CHARSET);
			CSVReader reader = new CSVReader(in, toChar(separator), toChar(quote), toChar(escape));
			String[] row = reader.readNext();

			Preconditions.checkNotNull(row, "Input file is empty!");

			Writer out = Files.newWriter(outputFile, OUTPUT_CHARSET);
			RDFWriter writer = Rio.createWriter(RDFFormat.forFileName(outputFile.getName(), RDFFormat.TURTLE), out);

			Template template = new Template(Arrays.asList(row), templateFile, writer);

			if (noHeader) {
				template.generate(row, writer);
			}

			while ((row = reader.readNext()) != null) {
				template.generate(row, writer);
			}

			writer.endRDF();

			reader.close();
			in.close();
			out.close();
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
		System.out.printf("Converted %,d rows to %,d triples%n", inputRows, outputTriples);
	}

	private static char toChar(String value) {
		Preconditions.checkArgument(value.length() == 1, "Expecting a single character but got %s", value);
		return value.charAt(0);
	}

	private static ParserConfig getParserConfig() {
		ParserConfig config = new ParserConfig();

		Set<RioSetting<?>> aNonFatalErrors = Sets.<RioSetting<?>> newHashSet(
		                BasicParserSettings.FAIL_ON_UNKNOWN_DATATYPES, BasicParserSettings.FAIL_ON_UNKNOWN_LANGUAGES);

		config.setNonFatalErrors(aNonFatalErrors);

		config.set(BasicParserSettings.FAIL_ON_UNKNOWN_DATATYPES, false);
		config.set(BasicParserSettings.FAIL_ON_UNKNOWN_LANGUAGES, false);
		config.set(BasicParserSettings.VERIFY_DATATYPE_VALUES, false);
		config.set(BasicParserSettings.VERIFY_LANGUAGE_TAGS, false);
		config.set(BasicParserSettings.VERIFY_RELATIVE_URIS, false);

		return config;
	}

	private class Template {
		private List<StatementGenerator> stmts = Lists.newArrayList();
		private List<ValueProvider> valueProviders = Lists.newArrayList();

		Template(List<String> cols, File templateFile, RDFWriter writer) throws Exception {
			parseTemplate(cols, templateFile, writer);
		}

		private String insertPlaceholders(List<String> cols, File templateFile) throws Exception {
			Pattern p = Pattern.compile("([\\$|\\#]\\{[^}]*\\})");

			Matcher m = p.matcher(Files.toString(templateFile, INPUT_CHARSET));
			StringBuffer sb = new StringBuffer();
			while (m.find()) {
				String var = m.group(1);
				String varName = var.substring(2, var.length() - 1);
				ValueProvider valueProvider = valueProviderFor(varName, cols);
				Preconditions.checkArgument(valueProvider != null, "Invalid template variable", var);
				valueProvider.isHash = (var.charAt(0) == '#');
				m.appendReplacement(sb, valueProvider.placeholder);
				valueProviders.add(valueProvider);
			}
			m.appendTail(sb);

			return sb.toString();
		}

		private ValueProvider valueProviderFor(String varName, List<String> cols) throws Exception {
			if (varName.equalsIgnoreCase("_ROW_")) {
				return new RowNumberProvider(); 
			}
			if (varName.equalsIgnoreCase("_UUID_")) {
				return new UUIDProvider(); 
			}
			
			int index = -1;			
			if (!noHeader) {
				index = cols.indexOf(varName);
			}
			else {
				try {
					index = Integer.parseInt(varName);
				}
				catch (NumberFormatException e) {
					if (varName.length() == 1) {
						char c = Character.toUpperCase(varName.charAt(0));
						if (c >= 'A' && c <= 'Z') {
							index = c - 'A';
						}
					}
				}
			}
			return index == -1 ? null : new RowValueProvider(index);
		}

		private void parseTemplate(List<String> cols, File templateFile, final RDFWriter writer) throws Exception {
			String templateStr = insertPlaceholders(cols, templateFile);

			RDFParser parser = Rio.createParser(RDFFormat.forFileName(templateFile.getName()));
			parser.setParserConfig(getParserConfig());
			parser.setRDFHandler(new RDFHandlerBase() {
				@SuppressWarnings("rawtypes")
				private Map<Value, ValueGenerator> generators = Maps.newHashMap();

				@Override
				public void startRDF() throws RDFHandlerException {
					writer.startRDF();
				}

				@Override
				public void handleNamespace(String prefix, String uri) throws RDFHandlerException {
					writer.handleNamespace(prefix, uri);
				}

				@Override
				public void handleStatement(Statement st) throws RDFHandlerException {
					ValueGenerator<Resource> subject = generatorFor(st.getSubject());
					ValueGenerator<URI> predicate = generatorFor(st.getPredicate());
					ValueGenerator<Value> object = generatorFor(st.getObject());
					stmts.add(new StatementGenerator(subject, predicate, object));
				}

				@SuppressWarnings({ "unchecked", "rawtypes" })
				private <V extends Value> ValueGenerator<V> generatorFor(V value) {
					ValueGenerator<V> generator = generators.get(value);
					if (generator != null) {
						return generator;
					}
					if (value instanceof BNode) {
						generator = (ValueGenerator<V>) new BNodeGenerator();
					}
					else {
						String str = value.toString();
						ValueProvider[] providers = providersFor(str);
						if (providers.length == 0) {
							generator = new ConstantValueGenerator(value);
						}
						else if (value instanceof URI) {
							generator = (ValueGenerator<V>) new TemplateURIGenerator(str, providers);
						}
						else {
							Literal literal = (Literal) value;
							generator = (ValueGenerator<V>) new TemplateLiteralGenerator(literal, providers);
						}
					}
					generators.put(value, generator);
					return generator;
				}

				private ValueProvider[] providersFor(String str) {
					List<ValueProvider> result = Lists.newArrayList();
					for (ValueProvider provider : valueProviders) {
						if (str.contains(provider.placeholder)) {
							result.add(provider);
						}  
                    }
					return result.toArray(new ValueProvider[0]);
				}
			});

			parser.parse(new StringReader(templateStr), "urn:");
		}

		public void generate(String[] row, RDFHandler handler) throws RDFHandlerException {
			inputRows++;
			for (StatementGenerator stmt : stmts) {
				outputTriples++;
				handler.handleStatement(stmt.generate(inputRows, row));
			}
		}
	}

	private static class StatementGenerator {
		private final ValueGenerator<Resource> subject;
		private final ValueGenerator<URI> predicate;
		private final ValueGenerator<Value> object;

		private StatementGenerator(ValueGenerator<Resource> s, ValueGenerator<URI> p, ValueGenerator<Value> o) {
			this.subject = s;
			this.predicate = p;
			this.object = o;
		}

		private Statement generate(int rowIndex, String[] row) {
			Resource s = subject.generate(rowIndex, row);
			URI p = predicate.generate(rowIndex, row);
			Value o = object.generate(rowIndex, row);
			return FACTORY.createStatement(s, p, o);
		}
	}

	private static abstract class ValueProvider {
		 private final String placeholder = UUID.randomUUID().toString();
		 private boolean isHash;

		public String provide(int rowIndex, String[] row) {
			 String value = provideValue(rowIndex, row);
			 if (value != null && isHash) {
				HashCode hash = Hashing.sha1().hashString(value, OUTPUT_CHARSET);
				value = BaseEncoding.base32Hex().omitPadding().lowerCase().encode(hash.asBytes());
			 }
			 return value;
		 }

		 protected abstract String provideValue(int rowIndex, String[] row);
	}

	private static class RowValueProvider extends ValueProvider {
		private final int colIndex;

		private RowValueProvider(int colIndex) {
			this.colIndex = colIndex;
		}

		protected String provideValue(int rowIndex, String[] row) {
			return row[colIndex];
		}
	}

	private static class RowNumberProvider extends ValueProvider {
		protected String provideValue(int rowIndex, String[] row) {
			return String.valueOf(rowIndex);
		}
	}

	private static class UUIDProvider extends ValueProvider {
		private String value = null;
		private int generatedRow = -1;
		
		protected String provideValue(int rowIndex, String[] row) {
			if (value == null || generatedRow != rowIndex) {
				value = UUID.randomUUID().toString();
				generatedRow = rowIndex;
			}
			return value;
		}
	}

	private interface ValueGenerator<V extends Value> {
		V generate(int rowIndex, String[] row);
	}

	private static class ConstantValueGenerator<V extends Value> implements ValueGenerator<V> {
		private final V value;

		private ConstantValueGenerator(V value) {
			this.value = value;
		}

		public V generate(int rowIndex, String[] row) {
			return value;
		}
	}

	private static class BNodeGenerator implements ValueGenerator<BNode> {
		private BNode value = null;
		private int generatedRow = -1;

		public BNode generate(int rowIndex, String[] row) {
			if (value == null || generatedRow != rowIndex) {
				value = FACTORY.createBNode();
				generatedRow = rowIndex;
			}
			return value;
		}
	}

	private static abstract class TemplateValueGenerator<V extends Value> implements ValueGenerator<V> {
		private final String template;
		private final ValueProvider[] providers;

		protected TemplateValueGenerator(String template, ValueProvider[] providers) {
			this.template = template;
			this.providers = providers;
		}

		protected String applyTemplate(int rowIndex, String[] row) {
			String result = template;
			for (ValueProvider provider : providers) {
				String value = provider.provide(rowIndex, row);
				if (value != null && !value.isEmpty()) {
					result = result.replace(provider.placeholder, value);
				}
			}
			return result;
		}
	}

	private static class TemplateURIGenerator extends TemplateValueGenerator<URI> {
		private TemplateURIGenerator(String template, ValueProvider[] providers) {
			super(template, providers);
		}

		public URI generate(int rowIndex, String[] row) {
			return FACTORY.createURI(applyTemplate(rowIndex, row));
		}
	}

	private static class TemplateLiteralGenerator extends TemplateValueGenerator<Literal> {
		private final URI datatype;
		private final String lang;

		private TemplateLiteralGenerator(Literal literal, ValueProvider[] providers) {
			super(literal.getLabel(), providers);

			this.datatype = literal.getDatatype();
			this.lang = literal.getLanguage();
		}

		public Literal generate(int rowIndex, String[] row) {
			String value = applyTemplate(rowIndex, row);
			return datatype == null ? lang == null ? FACTORY.createLiteral(value) : FACTORY.createLiteral(value, lang)
			                : FACTORY.createLiteral(value, datatype);
		}
	}

	public static void main(String[] args) throws Exception {
		try {
			Cli.<Runnable> builder("csv2rdf").withDescription("Converts a CSV file to RDF based on a given template")
			                .withDefaultCommand(CSV2RDF.class).withCommand(CSV2RDF.class).withCommand(Help.class)
			                .build().parse(args).run();
		}
		catch (Exception e) {
			System.err.println("ERROR: " + e.getMessage());
			e.printStackTrace();
		}
	}
}