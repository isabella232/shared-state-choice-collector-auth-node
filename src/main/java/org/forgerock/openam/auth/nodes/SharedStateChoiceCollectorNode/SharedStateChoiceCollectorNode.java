/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions copyright [year] [name of copyright owner]".
 *
 * Copyright 2018 David Adams david.adams@forgerock.com
 */

package org.forgerock.openam.auth.nodes.SharedStateChoiceCollectorNode;

import com.google.inject.assistedinject.Assisted;
import com.sun.identity.shared.debug.Debug;
import org.forgerock.guava.common.collect.ImmutableList;
import org.forgerock.json.JsonValue;
import org.forgerock.json.JsonValueException;
import org.forgerock.openam.annotations.sm.Attribute;
import org.forgerock.openam.auth.node.api.*;
import org.forgerock.openam.core.CoreWrapper;
import org.forgerock.util.i18n.PreferredLocales;

import javax.inject.Inject;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.ChoiceCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static java.util.Collections.emptyList;

/**
 * An authentication node presenting a prompt to the user with information from shared state, asking for their choice
 */
@Node.Metadata(outcomeProvider = SharedStateChoiceCollectorNode.SharedStateChoiceCollectorOutcomeProvider.class,
               configClass      = SharedStateChoiceCollectorNode.Config.class)
public class SharedStateChoiceCollectorNode extends AbstractDecisionNode {

    private final Config config;
    private final CoreWrapper coreWrapper;
    private final static String DEBUG_FILE = "SharedStateChoiceCollectorNode";
    protected Debug debug = Debug.getInstance(DEBUG_FILE);
    private final String START_VARIABLE_CHARS = "{";
    private final String END_VARIABLE_CHARS = "}";

    /**
     * Configuration for the node.
     */
    public interface Config {
        @Attribute(order = 100)
        String prompt();

        @Attribute(order = 200)
        List<String> choices();

        @Attribute(order = 300)
        String defaultChoice();
    }


    /**
     * Create the node.
     * @param config The service config.
     * @throws NodeProcessException If the configuration was not valid.
     */
    @Inject
    public SharedStateChoiceCollectorNode(@Assisted Config config, CoreWrapper coreWrapper) throws NodeProcessException {
        this.config = config;
        this.coreWrapper = coreWrapper;
    }

    /**
     * Process the node.
     * @param context
     * @return the next action to process
     * @throws NodeProcessException If the default choice was not valid
     */
    @Override
    public Action process(TreeContext context) throws NodeProcessException {
        if (context.hasCallbacks()) {
            ChoiceCallback choice = (ChoiceCallback) context.getAllCallbacks().get(0);
            int selectedIndex = choice.getSelectedIndexes()[0];
            debug.error("[" + DEBUG_FILE + "]: selected = " + selectedIndex);
            String selectedChoice = config.choices().get(selectedIndex);
            return goTo(selectedChoice).build();
        } else {
            int defaultChoice = config.choices().indexOf(config.defaultChoice());

            if (defaultChoice < 0) {
                throw new NodeProcessException("Invalid default choice");
            }

            String prompt = insertVars(config.prompt(), context.sharedState);
            List<Callback> callbacks = new ArrayList<>();
            callbacks.add(new ChoiceCallback(prompt, config.choices().toArray(new String[config.choices().size()]), defaultChoice, false));
            return send(ImmutableList.copyOf(callbacks)).build();
        }
    }

    /**
     * Replace variable placeholders with the appropriate values from shared state.
     * @param input The prompt string
     * @param sharedState The snapshot of shared state
     * @return the prompt string with variable placeholders replaced with values from shared state
     * @throws NodeProcessException If any of the variable paths defined were not valid
     */
    private String insertVars(String input, JsonValue sharedState) throws NodeProcessException{
        String str = input;
        List<String> vars = new ArrayList<>();
        int lastIndexStartVar = 0;

        while(lastIndexStartVar != -1){
            lastIndexStartVar = str.indexOf(START_VARIABLE_CHARS,lastIndexStartVar);
            int nextIndexEndVar = str.indexOf(END_VARIABLE_CHARS,lastIndexStartVar);
            if(lastIndexStartVar != -1 && nextIndexEndVar != -1){
                String var = str.substring(lastIndexStartVar + START_VARIABLE_CHARS.length(), nextIndexEndVar);
                vars.add(var);
                lastIndexStartVar += var.length() + END_VARIABLE_CHARS.length();
            }
        }

        for (String var : vars) {
            // If nested variables are stated, iterate each level to get the desired variable
            String[] nestedVars = var.split("\\.");
            if (nestedVars.length > 1) {
                int level = 0;
                JsonValue currentVar = sharedState.get(nestedVars[level]);

                while (level < nestedVars.length - 1) {
                    level++;
                    String nextVarName = nestedVars[level];
                    JsonValue nextVar = currentVar.get(nextVarName);

                    if (nextVar != null) {
                        currentVar = nextVar;
                    } else {
                        throw new NodeProcessException("Nested variable " + var + " not found in shared state");
                    }
                }

                String value = currentVar.get(0).toString();
                String repl = "\\" + START_VARIABLE_CHARS + var + "\\" + END_VARIABLE_CHARS;
                str = str.replaceAll(repl, value);
            } else {
                String value = sharedState.get(var).get(0).toString();
                if (value != null) {
                    String repl = "\\" + START_VARIABLE_CHARS + var + "\\" + END_VARIABLE_CHARS;
                    str = str.replaceAll(repl, value);
                } else {
                    throw new NodeProcessException("Variable " + var + " not found in shared state");
                }
            }
        }

        return str.replaceAll("\"", "");
    }

    /**
     * Build an action for a given outcome name
     * @param outcome
     * @return an action builder with the outcome
     */
    private Action.ActionBuilder goTo(String outcome) {
        return Action.goTo(outcome);
    }

    /**
     * Build an action with a list of callbacks
     * @param callbacks
     * @return an action builder with the callbacks
     */
    private Action.ActionBuilder send(ImmutableList callbacks) {
        return Action.send(callbacks);
    }

    /**
     *  An outcome provider mapping choices defined in config to outcomes
     */
    public static class SharedStateChoiceCollectorOutcomeProvider implements org.forgerock.openam.auth.node.api.OutcomeProvider {
        @Override
        public List<Outcome> getOutcomes(PreferredLocales locales, JsonValue nodeAttributes) {
            try {
                return nodeAttributes.get("choices").required()
                        .asList(String.class)
                        .stream()
                        .map(outcome -> new Outcome(outcome, outcome))
                        .collect(Collectors.toList());
            } catch (JsonValueException e) {
                return emptyList();
            }
        }
    }
}