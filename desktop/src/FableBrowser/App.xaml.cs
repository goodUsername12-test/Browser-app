using System.Windows;
using System.Windows.Media;
using System.Windows.Threading;
using FableBrowser.Browser;
using FableBrowser.Core;

namespace FableBrowser;

public partial class App : Application
{
    static readonly Dictionary<string, string> DarkPalette = new()
    {
        ["FbWindow"] = "#141218",
        ["FbChrome"] = "#0F0D13",
        ["FbToolbar"] = "#1D1B22",
        ["FbTab"] = "#00000000",
        ["FbTabHover"] = "#221F28",
        ["FbTabActive"] = "#1D1B22",
        ["FbText"] = "#ECE6F0",
        ["FbSubtle"] = "#A59CB0",
        ["FbAccent"] = "#A57BFF",
        ["FbAccentStrong"] = "#6D36C2",
        ["FbOmnibox"] = "#2A2531",
        ["FbOmniboxBorder"] = "#00000000",
        ["FbLine"] = "#2B2732",
        ["FbInfo"] = "#2A2140",
        ["FbIncognito"] = "#6D36C2",
        ["FbSuper"] = "#D93D73",
        ["FbDanger"] = "#FF6B6B",
        ["FbPopup"] = "#231F29",
        ["FbHover"] = "#2F2A37",
        ["FbPressed"] = "#3A3443",
        ["FbSelected"] = "#3B2D57"
    };

    static readonly Dictionary<string, string> LightPalette = new()
    {
        ["FbWindow"] = "#FDF8FD",
        ["FbChrome"] = "#EFE9F3",
        ["FbToolbar"] = "#FDF8FD",
        ["FbTab"] = "#00000000",
        ["FbTabHover"] = "#E4DDEA",
        ["FbTabActive"] = "#FDF8FD",
        ["FbText"] = "#1D1B20",
        ["FbSubtle"] = "#625B71",
        ["FbAccent"] = "#6D36C2",
        ["FbAccentStrong"] = "#6D36C2",
        ["FbOmnibox"] = "#EFE9F3",
        ["FbOmniboxBorder"] = "#00000000",
        ["FbLine"] = "#E0D9E6",
        ["FbInfo"] = "#EDE4FF",
        ["FbIncognito"] = "#6D36C2",
        ["FbSuper"] = "#D93D73",
        ["FbDanger"] = "#C62828",
        ["FbPopup"] = "#FFFFFF",
        ["FbHover"] = "#E8E1EE",
        ["FbPressed"] = "#DDD5E5",
        ["FbSelected"] = "#E3D6FA"
    };

    public static bool IsDarkTheme { get; private set; } = true;

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        DispatcherUnhandledException += OnDispatcherUnhandledException;
        AppDomain.CurrentDomain.UnhandledException += (_, args) => CrashLog.Write("Unhandled error", args.ExceptionObject as Exception);
        TaskScheduler.UnobservedTaskException += (_, args) =>
        {
            CrashLog.Write("Unobserved task error", args.Exception);
            args.SetObserved();
        };
        SettingsStore.Load();
        if (!SingleInstance.TryClaim(e.Args))
        {
            Shutdown();
            return;
        }
        ApplyTheme();
        WebAssets.Extract();
        Engine.Initialize();
        var window = new MainWindow(e.Args);
        MainWindow = window;
        SingleInstance.Listen(args => Dispatcher.InvokeAsync(() => window.HandleExternalArguments(args)));
        window.Show();
    }

    protected override void OnExit(ExitEventArgs e)
    {
        Library.FlushAll();
        Engine.Shutdown();
        SingleInstance.Release();
        if (FableBrowser.MainWindow.RestartRequested && Environment.ProcessPath is { } path)
        {
            try
            {
                System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(path, "--restarted") { UseShellExecute = false });
            }
            catch (Exception ex) when (ex is System.ComponentModel.Win32Exception or InvalidOperationException)
            {
                CrashLog.Write("Restart failed", ex);
            }
        }
        base.OnExit(e);
    }

    static void OnDispatcherUnhandledException(object sender, DispatcherUnhandledExceptionEventArgs e)
    {
        CrashLog.Write("Interface error", e.Exception);
        e.Handled = true;
    }

    public static void ApplyTheme()
    {
        IsDarkTheme = SettingsStore.Current.Theme switch
        {
            UiTheme.Light => false,
            UiTheme.Dark => true,
            _ => NativeMethods.SystemUsesDarkTheme()
        };
        var app = Current;
        app.ThemeMode = IsDarkTheme ? System.Windows.ThemeMode.Dark : System.Windows.ThemeMode.Light;
        foreach (var pair in IsDarkTheme ? DarkPalette : LightPalette)
        {
            var brush = new SolidColorBrush((Color)ColorConverter.ConvertFromString(pair.Value));
            brush.Freeze();
            app.Resources[pair.Key] = brush;
        }
    }
}
