/**
 * redpen: a text inspection tool
 * Copyright (c) 2014-2015 Recruit Technologies Co., Ltd. and contributors
 * (see CONTRIBUTORS.md)
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cc.redpen;

import cc.redpen.config.Configuration;
import cc.redpen.config.ValidatorConfiguration;
import cc.redpen.model.Document;
import cc.redpen.model.ListBlock;
import cc.redpen.model.ListElement;
import cc.redpen.model.Paragraph;
import cc.redpen.model.Section;
import cc.redpen.parser.DocumentParser;
import cc.redpen.parser.SentenceExtractor;
import cc.redpen.validator.ValidationError;
import cc.redpen.validator.Validator;
import cc.redpen.validator.ValidatorFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Validate all input files using appended Validators.
 */
public class RedPen {
    private static final Logger LOG = LoggerFactory.getLogger(RedPen.class);

    public static final String VERSION = "1.4.0";

    private final Configuration configuration;
    private final SentenceExtractor sentenceExtractor;
    private final List<Validator> validators;

    /**
     * constructs RedPen with specified config file.
     *
     * @param configFile config file
     * @throws RedPenException when failed to construct RedPen
     */
    public RedPen(File configFile) throws RedPenException {
        this(new ConfigurationLoader().load(configFile));
    }

    /**
     * constructs RedPen with specified config file path.
     *
     * @param configPath config file path
     * @throws RedPenException when failed to construct RedPen
     */
    public RedPen(String configPath) throws RedPenException {
        this(new ConfigurationLoader().loadFromResource(configPath));
    }

    /**
     * constructs RedPen with specified configuration.
     *
     * @param configuration configuration
     * @throws RedPenException when failed to construct RedPen
     */
    public RedPen(Configuration configuration) throws RedPenException {
        this.configuration = configuration;
        this.sentenceExtractor = new SentenceExtractor(this.configuration.getSymbolTable());
        this.validators = new ArrayList<>();
        for (ValidatorConfiguration config : configuration.getValidatorConfigs()) {
            validators.add(ValidatorFactory.getInstance(config, configuration.getSymbolTable()));
        }

    }

    /**
     * parse given input stream.
     *
     * @param parser      DocumentParser parser
     * @param InputStream content to parse
     * @return parsed document
     * @throws RedPenException when failed to parse input stream
     */
    public Document parse(DocumentParser parser, InputStream InputStream) throws RedPenException {
        return parser.parse(InputStream, sentenceExtractor, configuration.getTokenizer());
    }

    /**
     * parse given content.
     *
     * @param parser  DocumentParser parser
     * @param content content to parse
     * @return parsed document
     * @throws RedPenException when failed to parse input stream
     */
    public Document parse(DocumentParser parser, String content) throws RedPenException {
        return parser.parse(content, sentenceExtractor, configuration.getTokenizer());
    }

    /**
     * parse given files.
     *
     * @param parser DocumentParser parser
     * @param files  files to parse
     * @return parsed documents
     * @throws RedPenException when failed to parse input stream
     */
    public List<Document> parse(DocumentParser parser, File[] files) throws RedPenException {
        List<Document> documents = new ArrayList<>();
        for (File file : files) {
            documents.add(parser.parse(file, sentenceExtractor, configuration.getTokenizer()));
        }
        return documents;
    }

    /**
     * validate the input document collection. Note that this method call is NOT thread safe. RedPen instances need to be crated for each thread.
     *
     * @param documents input document collection generated by Parser
     * @return list of validation errors
     */
    public Map<Document, List<ValidationError>> validate(List<Document> documents) {
        Map<Document, List<ValidationError>> docErrorsMap = new HashMap<>();
        documents.forEach(e -> docErrorsMap.put(e, new ArrayList<>()));
        runDocumentValidators(documents, docErrorsMap);
        runSectionValidators(documents, docErrorsMap);
        runSentenceValidators(documents, docErrorsMap);
        return docErrorsMap;
    }

    /**
     * validate the input document collection. Note that this method call is NOT thread safe. RedPen instances need to be crated for each thread.
     *
     * @param document document to be validated
     * @return list of validation errors
     */
    public List<ValidationError> validate(Document document) {
        List<Document> documents = new ArrayList<>();
        documents.add(document);
        Map<Document, List<ValidationError>> documentListMap = validate(documents);
        return documentListMap.get(document);
    }

    /**
     * Get validators associated with this RedPen instance
     * @return validators
     */
    public List<Validator> getValidators() {
        return Collections.unmodifiableList(validators);
    }

    /**
     * Get the configuration object for this RedPen
     *
     * @return The configuration object for this RedPen
     */
    public Configuration getConfiguration() {
        return configuration;
    }

    private void runDocumentValidators(List<Document> documents, Map<Document, List<ValidationError>> docErrorsMap) {
        for (Document document : documents) {
            List<ValidationError> errors = new ArrayList<>();
            validators.forEach(e -> {e.setErrorList(errors); e.validate(document);});
            docErrorsMap.put(document, errors);
        }
    }

    private void runSectionValidators(List<Document> documents, Map<Document, List<ValidationError>> docErrorsMap) {
        // run Section PreProcessors to documents
        for (Document document : documents) {
            for (Section section : document) {
                validators.forEach(e -> e.preValidate(section));
            }
        }
        // run Section validator to documents
        for (Document document : documents) {
            for (Section section : document) {
                List<ValidationError> errors = docErrorsMap.get(document);
                validators.forEach(e -> {e.setErrorList(errors); e.validate(section);});
            }
        }
    }

    private void runSentenceValidators(List<Document> documents, Map<Document, List<ValidationError>> docErrorsMap) {
        // run Sentence PreProcessors to documents
        for (Document document : documents) {
            for (Section section : document) {
                // apply Sentence PreProcessors to section
                // apply paragraphs
                for (Paragraph paragraph : section.getParagraphs()) {
                    validators.forEach(e -> paragraph.getSentences().forEach(e::preValidate));
                }
                // apply to section header
                validators.forEach(e -> section.getHeaderContents().forEach(e::preValidate));

                // apply to lists
                for (ListBlock listBlock : section.getListBlocks()) {
                    for (ListElement listElement : listBlock.getListElements()) {
                        validators.forEach(e -> listElement.getSentences().forEach(e::preValidate));
                    }
                }
            }
        }
        // run Sentence Validators to documents
        for (Document document : documents) {
            for (Section section : document) {
                List<ValidationError> errors = docErrorsMap.get(document);

                // apply SentenceValidations to section
                // apply paragraphs
                for (Paragraph paragraph : section.getParagraphs()) {
                    validators.forEach(e ->{e.setErrorList(errors);  paragraph.getSentences().forEach(sentence -> e.validate(sentence));});
                }
                // apply to section header
                validators.forEach(e -> {e.setErrorList(errors); section.getHeaderContents().forEach(sentence -> e.validate(sentence));});
                // apply to lists
                for (ListBlock listBlock : section.getListBlocks()) {
                    for (ListElement listElement : listBlock.getListElements()) {
                        validators.forEach(e -> {e.setErrorList(errors); listElement.getSentences().forEach(sentence -> e.validate(sentence));});
                    }
                }
            }
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;

        RedPen redPen = (RedPen) o;

        if (configuration != null ? !configuration.equals(redPen.configuration) : redPen.configuration != null)
            return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = configuration != null ? configuration.hashCode() : 0;
        return result;
    }

    @Override
    public String toString() {
        return "RedPen{" +
                "configuration=" + configuration +
                ", sentenceExtractor=" + sentenceExtractor +
                ", validators=" + validators +
                '}';
    }
}
