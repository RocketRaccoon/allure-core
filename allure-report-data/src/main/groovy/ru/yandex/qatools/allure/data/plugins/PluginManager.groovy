package ru.yandex.qatools.allure.data.plugins

import com.google.common.reflect.ClassPath
import com.google.inject.Inject
import com.google.inject.Injector
import ru.yandex.qatools.allure.data.io.ReportWriter
import ru.yandex.qatools.allure.data.utils.PluginUtils

/**
 * Plugin manager helps you to find plugins and run
 * only plugins you need.
 * <p/>
 * @author Dmitry Baev charlie@yandex-team.ru
 *         Date: 17.02.15
 */
class PluginManager {

    /**
     * File with this name contains list of plugins with resources.
     */
    public static final String PLUGINS_JSON = "plugins.json"
    public static final String WIDGETS_JSON = "widgets.json"

    protected final Storage<PreparePlugin> preparePlugins

    protected final Storage<ProcessPlugin> processPlugins

    protected final List<DefaultPluginWithResources> pluginsWithResources

    protected final List<WithWidget> pluginsWithWidgets

    /**
     * Create an instance of plugin manager.
     */
    @Inject
    PluginManager(PluginLoader loader, Injector injector = null) {
        def plugins = load(loader, injector)
        preparePlugins = new Storage<>(filterByType(plugins, PreparePlugin))

        def processors = filterByType(plugins, ProcessPlugin)
        processPlugins = new Storage<>(processors)
        pluginsWithResources = filterByType(processors, DefaultPluginWithResources)
        pluginsWithWidgets = filterByType(processors, WithWidget)
    }

    /**
     * Find all prepare plugins an process given object for
     * each of found plugins.
     */
    public <T> void prepare(T object) {
        preparePlugins.get(object?.class)*.prepare(object)
    }

    /**
     * Find all process plugins an process given object for
     * each of found plugins.
     */
    public <T> void process(T object) {
        def plugins = processPlugins.get(object?.class)

        plugins.each {
            //copy each time, we can't use group operation
            it.process(PluginUtils.clone(object))
        }
    }

    /**
     * Write all plugin data using given report writer
     */
    public <T> void writePluginData(Class<T> type, ReportWriter writer) {
        writer.write(getData(type))
    }

    /**
     * Get plugin data for given type
     */
    protected List<PluginData> getData(Class<?> type) {
        processPlugins.get(type)*.pluginData?.flatten() as List<PluginData>
    }

    /**
     * Get list of names of plugins with resources
     */
    List<String> getPluginsWithResourcesNames() {
        pluginsWithResources.collect { plugin ->
            plugin.name
        }
    }

    /**
     * Write list of plugins with resources to {@link #PLUGINS_JSON}
     */
    void writePluginList(ReportWriter writer) {
        writer.write(new PluginData(PLUGINS_JSON, pluginsWithResourcesNames))
    }

    /**
     * Write plugins widgets to {@link #WIDGETS_JSON}
     */
    void writePluginWidgets(ReportWriter writer) {
        writer.write(new PluginData(WIDGETS_JSON, pluginsWithWidgets*.widget))
    }

    /**
     * Write plugin resources. For each plugin search resources using
     * {@link #findPluginResources(ru.yandex.qatools.allure.data.plugins.ProcessPlugin)}
     *
     * @see ReportWriter
     */
    void writePluginResources(ReportWriter writer) {
        pluginsWithResources.each { plugin ->
            def resources = findPluginResources(plugin)
            resources.each { resource ->
                writer.write(plugin.name, resource)
            }
        }
    }

    /**
     * Find all resources for plugin.
     */
    protected static List<URL> findPluginResources(ProcessPlugin plugin) {
        def path = plugin.class.canonicalName.replace('.', '/')
        def pattern = ~"^$path/.+\$"
        def result = []
        for (def resource : ClassPath.from(plugin.class.classLoader).resources) {
            if (resource.resourceName =~ pattern) {
                result.add(resource.url())
            }
        }
        result
    }

    /**
     * Load all plugins using given {@link PluginLoader} then remove all null plugins
     * and finally inject members to each plugin in case not null injector
     */
    protected static List<Plugin> load(PluginLoader loader, Injector injector) {
        def result = [] as List<Plugin>
        loader.loadPlugins().each {
            if (isValidPlugin(it)) {
                injector?.injectMembers(it)
                result.add(it)
            }
        }
        result
    }

    /**
     * Some checks for plugins.
     * @see DefaultPluginWithResources#isValid(java.lang.Class)
     */
    protected static boolean isValidPlugin(Plugin plugin) {
        return plugin && (plugin instanceof DefaultPluginWithResources ? DefaultPluginWithResources.isValid(plugin.class) : true)
    }

    /**
     * Find all plugins with specified type
     */
    protected static <T extends Plugin> List<T> filterByType(List<Plugin> plugins, Class<T> type) {
        plugins.findAll {
            type.isAssignableFrom(it.class)
        } as List<T>
    }

    /**
     * Internal storage for plugins.
     */
    protected class Storage<T extends Plugin> extends HashMap<Class, List<T>> {
        Storage(List<T> plugins) {
            plugins.each {
                containsKey(it.type) ? get(it.type).add(it) : put(it.type, [it])
            }
        }
    }
}
